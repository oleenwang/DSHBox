package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 在 **App 进程内** 启动 Genie/QNN，验证普通 App 能否真的跑通 HTP v81。
 *
 * 背景：shell 域用 Genie 已跑通（Qwen3-4B w4a16，cDSP domain 100000 出文）；
 * 但用 `run-as` 起的独立进程仍然 14001 —— 因为 `run-as` 启动的是**另一个进程**，
 * 它的 linker namespace 不是 App 的 classloader namespace，
 * 于是 QNN 内部 `dlopen("libcdsprpc.so")` 找不到（App 自带的 vendor 库只在 App namespace 里）。
 *
 * 这里的做法：在 App 进程里用 ProcessBuilder 拉起子进程，
 * 通过 LD_LIBRARY_PATH 显式带上 `applicationInfo.nativeLibraryDir`
 * （里面就有 App 自带的 libcdsprpc.so / vendor.qti.hardware.dsp-V1-ndk.so）。
 *
 * 需要事先把 genie 运行时与模型放进 App 私有目录：
 *   <dataDir>/g/    genie-t2t-run, libGenie.so, libQnnHtp*.so
 *   <dataDir>/k81/  genie_config.json, part{1..4}_of_4.bin, tokenizer.json ...
 */
object GenieNpuRunner {

    private const val TAG = "GenieNpu"

    /** 跑一次推理；返回完整输出（成功时含模型回答）。 */
    fun run(
        context: Context,
        prompt: String = "你好，请用一句话介绍你自己",
        config: String = "cfg32.json",
        timeoutMs: Long = 180_000,
    ): String {
        val dataDir = context.applicationInfo.dataDir
        val gDir = File(dataDir, "g")
        val kDir = File(dataDir, "k81")
        val bin = File(gDir, "genie-t2t-run")
        if (!bin.exists()) return "MISSING: ${bin.absolutePath}"
        if (!kDir.exists()) return "MISSING: ${kDir.absolutePath}"

        val nativeDir = context.applicationInfo.nativeLibraryDir
        Log.i(TAG, "dataDir=$dataDir")
        Log.i(TAG, "nativeLibraryDir=$nativeDir")
        Log.i(TAG, "libcdsprpc present=${File(nativeDir, "libcdsprpc.so").exists()}")

        val pb = ProcessBuilder(bin.absolutePath, "-c", config, "-p", prompt)
        pb.directory(kDir)
        pb.redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:${gDir.absolutePath}"
        pb.environment()["ADSP_LIBRARY_PATH"] = gDir.absolutePath

        val proc = pb.start()
        val out = StringBuilder()
        val t0 = System.currentTimeMillis()
        proc.inputStream.bufferedReader().forEachLine { line ->
            out.append(line).append('\n')
            Log.i(TAG, line)
        }
        val code = proc.waitFor()
        val ms = System.currentTimeMillis() - t0
        Log.i(TAG, "exit=$code elapsed=${ms}ms")
        return out.toString()
    }
}
