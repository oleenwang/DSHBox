package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * NPU 可达性最终探针（真实 App 域，非 shell 域）。
 *
 * 背景：QNN/Genie 在 shell 域能跑通 HTP v81（Qwen3-4B w4a16 已实测生成），
 * 但 App 域报 `Failed to create device: 14001`
 * （= QNN_DSP_PERF_INFRASTRUCTURE_ERROR_INVALID_INPUT，power/DCVS 配置失败）。
 *
 * 本探针用预编译的 `libnpu-probe.so` 逐级定位 App 到底卡在哪一层：
 *  1. 直接 open /dev/fastrpc-cdsp  —— 预期被 neverallow 挡住
 *  2. dlopen libcdsprpc.so          —— 厂商 HAL 客户端库是否可用
 *  3. CDSP_open(0)                  —— HAL fallback 能否真正拿到 DSP session
 *  4. dlopen libQnnHtp.so           —— QNN 官方路径符号可见性
 *
 * 判读：
 *  - `CDSP_open` 返回 >= 0 → App 域经厂商 HAL 已可触达 cDSP（关键结论）。
 *  - dlopen 成功但 CDSP_open 失败 → 缺 binder 调用权限或需额外 AIDL 客户端。
 */
object NpuReachabilityProbe {

    private const val TAG = "NpuReachProbe"
    private const val LIB = "npu-probe"

    /** 由 libnpu-probe.so 提供实现（见 npu_probe_jni.c）。 */
    @Suppress("unused")
    private external fun runNative(): String

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
            "ctx=$ctx fastrpc(exists=${node.exists()} canRead=${node.canRead()} canWrite=${node.canWrite()}) " +
                "uid=${android.os.Process.myUid()}",
        )

        val out = runCatching { runNative() }
            .onFailure { Log.e(TAG, "native probe failed: ${it.message}") }
            .getOrElse { return }

        Log.i(TAG, "=== native probe output ===")
        out.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
        Log.i(TAG, "=== end ===")

        // 接着在 App 进程内拉起 Genie，验证 HTP v81 能否真正出文
        runCatching {
            val o = GenieNpuRunner.run(context)
            Log.i(TAG, "=== genie output ===")
            o.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
        }.onFailure { Log.e(TAG, "genie failed: ${it.message}") }
    }
}
