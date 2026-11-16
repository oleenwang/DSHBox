package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 在宿主进程里跑一次 LiteRT-LM（Google 官方端侧 LLM 运行时）的 NPU 推理。
 *
 * 用反射调用 `com.google.ai.edge.litertlm.*`：
 * AAR 的 Kotlin 默认参数会生成多个重载，签名随版本漂移；反射可避免编译期失败，
 * 并把真实异常原样打出来。所有步骤分步 log，失败时能立刻看出卡在哪一层。
 */
object LiteRtLmProbe {

    private const val TAG = "LrtLmProbe"
    private const val MODEL = "/sdcard/Download/qwen35_mm_q8_ekv2048.litertlm"
    private const val PROMPT = "用一句话说明端侧推理的好处。"
    private const val MAX_TOKENS = 64
    private const val MAX_NUM_TOKENS = 2048

    private const val DIR = "/sdcard/Download"

    fun run(ctx: Context) {
        val models = runCatching {
            File(DIR).listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.length() > 50L * 1024 * 1024 }
                ?.sortedBy { it.length() }
        }.getOrNull().orEmpty()
        Log.i(TAG, "found ${models.size} model(s) in $DIR")
        models.forEach { Log.i(TAG, "  candidate: ${it.name} ${it.length() / 1_000_000} MB") }
        if (models.isEmpty()) { Log.w(TAG, "no model — skip"); return }
        val app = ctx.applicationContext
        Thread {
            for (m in models) {
                Log.i(TAG, "===== trying ${m.name} =====")
                runCatching { probe(app, m) }
                    .onFailure { Log.e(TAG, "probe fatal for ${m.name}: ${it.cause ?: it}") }
            }
        }.apply { name = "lrtlm-probe"; start() }
    }

    private fun probe(ctx: Context, model: File) {
        abTest(ctx, model)
    }

    /** 同一提示词分别用 NPU / CPU 后端跑，比较耗时 —— 用于判定 NPU 是否真的在算。 */
    private fun abTest(ctx: Context, model: File) {
        if (model.name.contains("_mm_")) { Log.i(TAG, "skip broken mm model ${model.name}"); return }
        val prompt = "说一句话"
        for (tag in listOf("NPU", "CPU")) {
            val t0 = System.nanoTime()
            val r = runBackend(ctx, model, tag, prompt)
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "AB[$tag] ${model.name} total=$ms ms result=${r ?: "<fail>"}")
        }
    }

    private fun runBackend(ctx: Context, model: File, backend: String, prompt: String): String? {
        val libDir = ctx.applicationInfo.nativeLibraryDir
        return runCatching {
            val backendCls = Class.forName("com.google.ai.edge.litertlm.Backend")
            val cfgCls = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
            val engCls = Class.forName("com.google.ai.edge.litertlm.Engine")
            val be = if (backend == "NPU") {
                Class.forName("com.google.ai.edge.litertlm.Backend\$NPU")
                    .getConstructor(String::class.java).newInstance(libDir)
            } else {
                val b = Class.forName("com.google.ai.edge.litertlm.Backend\$CPU")
                runCatching { b.getConstructor(Integer::class.java).newInstance(Integer.valueOf(4)) }
                    .getOrElse { b.getDeclaredConstructor().newInstance() }
            }
            Log.i(TAG, "AB[$backend] backend=$be")
            val cfg = cfgCls
                .getConstructor(String::class.java, backendCls, backendCls, backendCls, Integer::class.java)
                .newInstance(model.absolutePath, be, null, null, Integer.valueOf(MAX_NUM_TOKENS))
            val eng = engCls.getConstructor(cfgCls).newInstance(cfg)
            engCls.getMethod("initialize").invoke(eng)
            val convCfgCls = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
            val conv = engCls.methods.first { it.name == "createConversation" && it.parameterTypes.size == 1 }
                .invoke(eng, convCfgCls.getDeclaredConstructor().newInstance())
            val send = conv.javaClass.methods.first {
                it.name == "sendMessage" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java && Map::class.java.isAssignableFrom(it.parameterTypes[1])
            }
            val t = System.nanoTime()
            val msg = send.invoke(conv, prompt, emptyMap<String, Any>())
            val inferMs = (System.nanoTime() - t) / 1_000_000
            val text = runCatching { msg.javaClass.getMethod("getText").invoke(msg) as String }
                .getOrElse { msg.toString() }
            runCatching { engCls.getMethod("close").invoke(eng) }
            Log.i(TAG, "AB[$backend] infer=$inferMs ms")
            text
        }.onFailure { Log.e(TAG, "AB[$backend] failed: ${it.cause ?: it}") }.getOrNull()
    }

    private fun legacyProbe(ctx: Context, model: File) {
        val libDir = ctx.applicationInfo.nativeLibraryDir
        Log.i(TAG, "nativeLibraryDir=$libDir")

        val backendCls = cls("com.google.ai.edge.litertlm.Backend")
        val npuCls = cls("com.google.ai.edge.litertlm.Backend\$NPU")
        val cfgCls = cls("com.google.ai.edge.litertlm.EngineConfig")
        val engCls = cls("com.google.ai.edge.litertlm.Engine")

        // 1) 后端：NPU(nativeLibraryDir) —— QNN 库从 APK 自己的库目录加载
        val npu = runCatching {
            npuCls.getConstructor(String::class.java).newInstance(libDir)
        }.onFailure { Log.e(TAG, "Backend.NPU failed: ${it.cause ?: it}") }.getOrNull()
        Log.i(TAG, "Backend.NPU=${npu != null}")

        // 2) EngineConfig(modelPath, backend, vision, audio, maxNumTokens)
        //    必须显式给 maxNumTokens：2 参重载沿用默认值（极小），会让任何提示词都超限
        //    报 "Exceeding the maximum number of tokens allowed: 17 >= 3"。
        val intCls = Integer::class.java
        val cfg = runCatching {
            cfgCls
                .getConstructor(String::class.java, backendCls, backendCls, backendCls, intCls)
                .newInstance(model.absolutePath, npu, null, null, Integer.valueOf(MAX_NUM_TOKENS))
        }.recoverCatching {
            Log.w(TAG, "5-arg EngineConfig failed (${it.cause ?: it}), fallback to 2-arg")
            cfgCls.getConstructor(String::class.java, backendCls).newInstance(model.absolutePath, npu)
        }.onFailure { Log.e(TAG, "EngineConfig failed: ${it.cause ?: it}") }.getOrNull()
        Log.i(TAG, "EngineConfig=${cfg != null}")
        if (cfg != null) {
            for (getter in listOf("getMaxNumTokens", "getMaxNumImages", "getModelPath", "getBackend", "getCacheDir")) {
                val v = runCatching { cfgCls.getMethod(getter).invoke(cfg) }.getOrNull()
                Log.i(TAG, "cfg.$getter=$v")
            }
        }
        if (cfg == null || npu == null) return

        // 3) Engine(config)
        val engCtor = engCls.constructors.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == cfgCls }
        if (engCtor == null) {
            Log.e(TAG, "Engine ctor not found; ctors=" + engCls.constructors.joinToString { it.parameterTypes.joinToString(",") { p -> p.simpleName } })
            return
        }
        val engine = runCatching { engCtor.newInstance(cfg) }
            .onFailure { Log.e(TAG, "Engine ctor threw: ${it.cause ?: it}") }.getOrNull() ?: return
        Log.i(TAG, "Engine created")

        try {
            // 4) initialize()
            val t0 = System.nanoTime()
            runCatching { engCls.getMethod("initialize").invoke(engine) }
                .onFailure { Log.e(TAG, "initialize failed: ${it.cause ?: it}") }
                .onSuccess { Log.i(TAG, "initialize ok in ${(System.nanoTime() - t0) / 1_000_000} ms") }
            val inited = runCatching { engCls.getMethod("isInitialized").invoke(engine) }.getOrNull()
            Log.i(TAG, "isInitialized=$inited")

            // 5) Conversation
            val convCfgCls = cls("com.google.ai.edge.litertlm.ConversationConfig")
            val convCfg = runCatching { convCfgCls.getDeclaredConstructor().newInstance() }.getOrNull()
            Log.i(TAG, "ConversationConfig=${convCfg != null}")
            val createConv = engCls.methods.firstOrNull { it.name == "createConversation" && it.parameterTypes.size == 1 }
            if (createConv == null || convCfg == null) {
                Log.e(TAG, "createConversation not found")
                return
            }
            val conv = runCatching { createConv.invoke(engine, convCfg) }
                .onFailure { Log.e(TAG, "createConversation threw: ${it.cause ?: it}") }.getOrNull() ?: return
            Log.i(TAG, "Conversation created")

            // 6) sendMessage(String, Map): Message —— 同步阻塞版
            val convCls = conv.javaClass
            val send = convCls.methods.firstOrNull {
                it.name == "sendMessage" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java && Map::class.java.isAssignableFrom(it.parameterTypes[1])
            }
            if (send == null) {
                Log.e(TAG, "sendMessage(String,Map) not found; methods=" +
                    convCls.methods.filter { it.name.startsWith("send") }.joinToString { it.name })
                return
            }
            for (p in listOf("Hi", PROMPT)) {
                val start = System.nanoTime()
                val got = runCatching { send.invoke(conv, p, emptyMap<String, Any>()) }
                    .onFailure { Log.e(TAG, "send(\"$p\") threw: ${it.cause ?: it}") }
                    .getOrNull()
                val el = (System.nanoTime() - start) / 1_000_000
                Log.i(TAG, "send(prompt=\"$p\") -> ${got != null} in $el ms")
                if (got != null) {
                    val text = runCatching { got.javaClass.getMethod("getText").invoke(got) }.getOrNull() ?: got.toString()
                    Log.i(TAG, "NPU RESULT[$el ms]: $text")
                }
            }
            runCatching {
                val biM = convCls.getMethod("getBenchmarkInfo")
                Log.i(TAG, "benchmarkInfo=" + biM.invoke(conv))
            }
        } finally {
            runCatching { engCls.getMethod("close").invoke(engine) }
        }
    }

    private fun cls(name: String): Class<*> = Class.forName(name)
}
