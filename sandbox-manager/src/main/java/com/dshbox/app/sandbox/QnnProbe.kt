package com.dshbox.app.sandbox

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 在**宿主进程**里探测 QNN HTP（Hexagon NPU）可达性。
 *
 * 背景：guest（proot 内）看不到 vendor 分区、也打不开 fastrpc 设备节点，
 * 因此「能不能用 NPU」只能在 App 自己的进程里回答。本探针只做只读探测：
 * 列出随包 QNN 库、尝试加载、并用 QnnDelegate 走一次 HTP 后端初始化。
 *
 * 全程用反射调用 `com.qualcomm.qti.QnnDelegate`：
 * 该 API 在不同版本间签名可能漂移，反射可避免编译期失败，且把真实异常原样打出来。
 */
object QnnProbe {

    private const val TAG = "QnnProbe"

    fun run(ctx: Context) {
        val sb = StringBuilder()
        sb.append("brand=${Build.BRAND} api=${Build.VERSION.SDK_INT} ")
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        sb.append("nativeLibDir=$nativeDir")

        // 1) 随包 QNN 库清单
        val qnnLibs = runCatching {
            File(nativeDir).list()?.filter { it.contains("qnn", ignoreCase = true) }?.sorted()
        }.getOrNull().orEmpty()
        sb.append(" qnnLibCount=${qnnLibs.size}")
        Log.i(TAG, sb.toString())
        qnnLibs.forEach { Log.i(TAG, "lib: $it") }
        if (qnnLibs.isEmpty()) {
            Log.w(TAG, "no QNN libs in nativeLibraryDir — dependency not packaged?")
            return
        }

        // 2) 独立加载基础库，区分「加载失败」与「设备打不开」
        for (name in listOf("QnnHtp", "QnnHtpV81Stub", "QnnSystem")) {
            val ok = runCatching { System.loadLibrary(name) }.isSuccess
            Log.i(TAG, "loadLibrary($name)=$ok")
        }

        // 3) 反射调用 QnnDelegate
        val cls = runCatching { Class.forName("com.qualcomm.qti.QnnDelegate") }.getOrNull()
        if (cls == null) {
            Log.w(TAG, "QnnDelegate class not found")
            return
        }
        logStaticBool(cls, "isAvailable")
        logStaticBool(cls, "isPlatformSupported")

        val skelDir = File(nativeDir)
        val v81 = File(skelDir, "libQnnHtpV81Skel.so")
        Log.i(TAG, "v81Skel present=${v81.isFile} size=${if (v81.isFile) v81.length() else 0}")

        runCatching {
            val optCls = Class.forName("com.qualcomm.qti.QnnDelegate\$Options")
            val btCls = Class.forName("com.qualcomm.qti.QnnDelegate\$Options\$BackendType")
            val opt = optCls.getDeclaredConstructor().newInstance()

            @Suppress("UNCHECKED_CAST")
            val htp = java.lang.Enum.valueOf(btCls as Class<out Enum<*>>, "HTP_BACKEND")
            optCls.getMethod("setBackendType", btCls).invoke(opt, htp)
            runCatching {
                optCls.getMethod("setSkelLibraryDir", String::class.java).invoke(opt, nativeDir)
            }.onFailure { Log.w(TAG, "setSkelLibraryDir failed: ${it.cause ?: it}") }

            // createDelegate 可能是静态方法，也可能是实例方法
            val create = cls.methods.firstOrNull {
                it.name == "createDelegate" && it.parameterTypes.size == 1
            }
            if (create == null) {
                Log.w(TAG, "createDelegate not found; methods=" +
                    cls.methods.joinToString(",") { it.name })
                return@runCatching
            }
            val target = if (java.lang.reflect.Modifier.isStatic(create.modifiers)) null else cls.getDeclaredConstructor().newInstance()
            val delegate = runCatching { create.invoke(target, opt) }
                .onFailure { Log.e(TAG, "createDelegate(HTP) FAILED: ${it.cause ?: it}") }
                .getOrNull()
            Log.i(TAG, "createDelegate(HTP)=${delegate != null}")
            if (delegate != null) {
                Log.i(TAG, "QNN HTP delegate created — NPU reachable from app process")
            }
        }.onFailure { Log.e(TAG, "reflection path failed: ${it.cause ?: it}") }
    }

    private fun logStaticBool(cls: Class<*>, name: String) {
        val v = runCatching { cls.getMethod(name).invoke(null) }
            .onFailure { Log.w(TAG, "$name() threw: ${it.cause ?: it}") }
            .getOrNull()
        Log.i(TAG, "$name()=$v")
    }
}
