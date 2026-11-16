package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * NPU 可达性探针（真实 App 域，untrusted_app_34）。
 *
 * 已验证的事实链：
 *  - appdomain 对 vendor_qdsp_device 只授 ioctl+read，无 open
 *    → 直接 open("/dev/fastrpc-cdsp") 必 EACCES。
 *  - /vendor/etc/public.libraries.txt 把 libcdsprpc.so 列为厂商公开库
 *    → App 可直接 dlopen 到 /vendor/lib64 那份，无需把副本打进 APK
 *      （打副本反而遮蔽真库并引入 libvndksupport 之类坏依赖）。
 *  - appdomain 读 /sys/devices/soc0/sku 被拒（vendor_sysfs_soc read）。
 *
 * 因此判据不是「能不能打开设备节点」，而是：
 *   QNN 能否在 App 进程内 backendCreate + deviceCreate 成功。
 */
object NpuReachabilityProbe {

    private const val TAG = "NpuReachProbe"
    private const val LIB = "npu-qnn"

    /** 来自 libnpu-qnn.so（见 npu_probe_jni.c）。 */
    @Suppress("unused") private external fun runNative(): String
    @Suppress("unused") private external fun runQnnNative(libDir: String, skelDir: String): String
    @Suppress("unused") private external fun runGenieNative(cfgPath: String, prompt: String): String

    fun run(context: Context) {
        runCatching {
            System.loadLibrary(LIB)
            Log.i(TAG, "native lib loaded: $LIB")
        }.onFailure {
            Log.e(TAG, "native lib load failed: ${it.message}")
            return
        }

        val ctx = runCatching { File("/proc/self/attr/current").readText().trim() }.getOrDefault("?")
        val node = File("/dev/fastrpc-cdsp")
        Log.i(
            TAG,
            "ctx=$ctx fastrpc(exists=${node.exists()} canRead=${node.canRead()} " +
                "canWrite=${node.canWrite()}) uid=${android.os.Process.myUid()}",
        )

        // 1) 可达性探针
        runCatching { runNative() }
            .onSuccess { out ->
                Log.i(TAG, "=== probe output ===")
                out.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
                Log.i(TAG, "=== end probe ===")
            }
            .onFailure { Log.e(TAG, "probe failed: ${it.message}") }

        // 2) 关键判定（同时设好 ADSP_LIBRARY_PATH；建完即释放，避免独占 DSP）：App 域能否建成 QNN HTP 设备
        val libDir = context.applicationInfo.nativeLibraryDir
        Log.i(TAG, "nativeLibraryDir=$libDir")
        runCatching { runQnnNative(libDir, libDir) }
            .onSuccess { out ->
                Log.i(TAG, "=== qnn output ===")
                out.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
                Log.i(TAG, "=== end qnn ===")
            }
            .onFailure { Log.e(TAG, "qnn failed: ${it.message}") }

        // 3) 进程内 Genie 推理（依赖上一步设好的 ADSP_LIBRARY_PATH）
        val cfg = File(context.filesDir, "k81/cfg32.json")
        if (cfg.exists()) {
            runCatching { runGenieNative(cfg.absolutePath, "你好，用一句话介绍你自己") }
                .onSuccess { out ->
                    Log.i(TAG, "=== genie output ===")
                    out.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
                    Log.i(TAG, "=== end genie ===")
                }
                .onFailure { Log.e(TAG, "genie failed: ${it.message}") }
        } else {
            Log.i(TAG, "genie skipped: ${cfg.absolutePath} 不存在")
        }
    }
}
