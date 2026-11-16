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

    fun run(ctx: Context) {
        val f = File(MODEL)
        Log.i(TAG, "model=$MODEL exists=${f.isFile} size=${if (f.isFile) f.length() else 0}")
        if (!f.isFile || f.length() < 1024 * 1024) {
            Log.w(TAG, "model missing/too small — skip inference probe")
            return
        }
        val app = ctx.applicationContext
        Thread {
            runCatching { probe(app, f) }
                .onFailure { Log.e(TAG, "probe fatal: ${it}", it) }
        }.apply { name = "lrtlm-probe"; start() }
    }

    private fun probe(ctx: Context, model: File) {
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

        // 2) EngineConfig(modelPath, backend)（@JvmOverloads 的 2 参重载）
        val cfg = runCatching {
            cfgCls.getConstructor(String::class.java, backendCls).newInstance(model.absolutePath, npu)
        }.onFailure { Log.e(TAG, "EngineConfig failed: ${it.cause ?: it}") }.getOrNull()
        Log.i(TAG, "EngineConfig=${cfg != null}")
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
            val start = System.nanoTime()
            val msg = runCatching { send.invoke(conv, PROMPT, emptyMap<String, Any>()) }
                .onFailure { Log.e(TAG, "sendMessage threw: ${it.cause ?: it}") }.getOrNull()
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i(TAG, "sendMessage -> ${msg != null} in $ms ms")
            if (msg != null) {
                val text = runCatching { msg.javaClass.getMethod("getText").invoke(msg) }.getOrNull()
                    ?: msg.toString()
                Log.i(TAG, "NPU RESULT[$ms ms]: $text")
            }
        } finally {
            runCatching { engCls.getMethod("close").invoke(engine) }
        }
    }

    private fun cls(name: String): Class<*> = Class.forName(name)
}
