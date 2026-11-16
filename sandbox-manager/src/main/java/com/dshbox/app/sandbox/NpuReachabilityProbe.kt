package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * NPU 可达性探针（真实 App 域，untrusted_app_34）。
 *
 * 已确证的事实链：
 *  - appdomain 对 vendor_qdsp_device 只授 ioctl+read，无 open
 *    → 直接 open("/dev/fastrpc-cdsp") 必 EACCES。
 *  - /vendor/etc/public.libraries.txt 把 libcdsprpc.so 列为厂商公开库
 *    → App 可直接 dlopen 到 /vendor/lib64 那份，无需打包副本。
 *
 * 判据是「QNN 能否在 App 进程内 deviceCreate」。
 *
 * 本版把每一步同时写进 filesDir/npu-trace.log（追加 + flush），
 * 因为本机 logcat 会被 PV-MON 洪水冲掉，实测探针一行日志都取不到。
 */
object NpuReachabilityProbe {

    private const val TAG = "NpuReachProbe"
    private const val LIB = "npu-qnn"
    private const val TRACE = "npu-trace.log"

    @Suppress("unused") private external fun runNative(): String
    @Suppress("unused") private external fun setenvLibs(libDir: String, skelDir: String): String
    @Suppress("unused") private external fun runQnnNative(libDir: String, skelDir: String): String
    @Suppress("unused") private external fun runGenieNative(
        cfgPath: String, prompt: String, tracePath: String,
    ): String

    private fun trace(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        runCatching {
            File(ctx.filesDir, TRACE).appendText(msg + "\n")
        }
    }

    private fun traceBlock(ctx: Context, title: String, body: String) {
        trace(ctx, "=== $title ===")
        body.lineSequence().filter { it.isNotBlank() }.forEach { trace(ctx, it) }
        trace(ctx, "=== end $title ===")
    }

    fun run(context: Context) {
        trace(context, "[t] run() 进入 uid=${android.os.Process.myUid()}")

        val ctx = runCatching { File("/proc/self/attr/current").readText().trim() }.getOrDefault("?")
        trace(context, "[t] ctx=$ctx")
        trace(context, "[t] 即将 System.loadLibrary(\"$LIB\")")
        val loaded = runCatching {
            System.loadLibrary(LIB); true
        }.getOrElse {
            trace(context, "[t] loadLibrary 失败: ${it.message}"); false
        }
        if (!loaded) return
        trace(context, "[t] loadLibrary 成功")

        runCatching { runNative() }
            .onSuccess { traceBlock(context, "probe output", it) }
            .onFailure { trace(context, "[t] probe 失败: ${it.message}") }

        val libDir = context.applicationInfo.nativeLibraryDir
        val tracePath = File(context.filesDir, TRACE).absolutePath
        trace(context, "[t] nativeLibraryDir=$libDir")
        runCatching { setenvLibs(libDir, libDir) }
            .onSuccess { traceBlock(context, "env", it) }
            .onFailure { trace(context, "[t] setenv 失败: ${it.message}") }

        // Genie 先跑：它是 DSP 的唯一使用者，避免被前面的 QNN 设备残留状态卡住
        val cfg = File(context.filesDir, "k81/cfg32.json")
        trace(context, "[t] 模型配置 ${cfg.absolutePath} exists=${cfg.exists()}")
        if (cfg.exists()) {
            trace(context, "[t] 进入 runGenieNative（每步会直接写本文件）")
            runCatching { runGenieNative(cfg.absolutePath, "你好，用一句话介绍你自己", tracePath) }
                .onSuccess { traceBlock(context, "genie output", it) }
                .onFailure { trace(context, "[t] genie 失败: ${it.message}") }
        }

        // 最后做 QNN 设备创建判定（建完即释放）
        runCatching { runQnnNative(libDir, libDir) }
            .onSuccess { traceBlock(context, "qnn output", it) }
            .onFailure { trace(context, "[t] qnn 失败: ${it.message}") }

        trace(context, "[t] run() 结束")
    }
}
