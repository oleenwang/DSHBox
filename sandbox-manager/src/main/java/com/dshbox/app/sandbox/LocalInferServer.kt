package com.dshbox.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在 App 进程内提供 **OpenAI 兼容**的本地推理端点，后端是 LiteRT-LM 的 NPU 通道。
 *
 * 目的：让 DSH（proot 内）不用碰设备节点、不用 root，就能通过
 * `http://127.0.0.1:18081/v1/chat/completions` 用到 Hexagon NPU。
 *
 * 只实现最小可用子集：
 *  - `POST /v1/chat/completions`（`stream=false`）
 *  - `GET  /v1/models`
 *  - `GET  /health`
 *
 * LiteRT-LM 的 Java API 用反射调用（AAR 的 Kotlin 默认参数会生成多个重载，
 * 反射可避免签名漂移导致编译失败）。Engine 只初始化一次，之后复用同一 Conversation。
 */
object LocalInferServer {

    private const val TAG = "LocalInfer"
    const val PORT = 18081

    private const val MODEL_DIR = "/sdcard/Download"
    private const val PREFERRED = "raon-nano.litertlm"
    private const val MAX_NUM_TOKENS = 2048

    private val started = AtomicBoolean(false)

    // 引擎状态（惰性初始化，只建一次）
    private var engine: Any? = null
    private var conversation: Any? = null
    private var sendMethod: java.lang.reflect.Method? = null
    private var convCls: Class<*>? = null
    private var modelName: String = "(none)"
    private var initErr: String? = null

    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread { serve(app) }.apply { name = "local-infer"; isDaemon = true; start() }
        Log.i(TAG, "server thread started on 127.0.0.1:$PORT")
    }

    // ---------- HTTP ----------

    private fun serve(ctx: Context) {
        val addr = InetAddress.getByName("127.0.0.1")
        // 外层守护循环：任何异常都不许让监听永久消失（此前线程一死，ServerSocket 被 GC 回收）。
        while (true) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket(PORT, 50, addr)
                Log.i(TAG, "listening on http://127.0.0.1:$PORT/v1")
                while (true) {
                    // 单线程串行处理：本地单用户场景足够，且避免每个请求建线程
                    // （模型常驻约 1.8 GB，线程创建失败会让整个监听崩掉）。
                    try {
                        ss.accept().use { s -> handle(ctx, s) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "accept/handle: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "serve loop died, rebinding in 2s: $t")
            } finally {
                runCatching { ss?.close() }
            }
            try { Thread.sleep(2000) } catch (ie: InterruptedException) { return }
        }
    }

    private fun handle(ctx: Context, sock: Socket) {
        sock.use { s ->
            s.soTimeout = 120_000
            val ins = s.getInputStream()
            val out = s.getOutputStream()
            val head = readHead(ins) ?: return
            val firstLine = head.lineSequence().firstOrNull().orEmpty()
            val parts = firstLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val len = head.lineSequence()
                .firstOrNull { it.lowercase().startsWith("content-length:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = if (len > 0) readExactly(ins, len) else ""

            when {
                method == "GET" && path == "/health" -> respond(out, 200,
                    """{"status":"ok","model":"$modelName","backend":"NPU","ready":${engine != null}}""")
                method == "GET" && path == "/v1/models" -> respond(out, 200,
                    """{"object":"list","data":[{"id":"$modelName","object":"model","owned_by":"dshbox-local"}]}""")
                method == "POST" && path == "/v1/chat/completions" -> {
                    val prompt = extractLastUserContent(body)
                    if (prompt.isBlank()) { respond(out, 400, """{"error":"no user message"}"""); return }
                    val t0 = System.currentTimeMillis()
                    val text = runCatching { ensureEngine(ctx) ?: error(initErr ?: "engine init failed") }
                        .mapCatching { infer(prompt) }.getOrElse { e ->
                            Log.e(TAG, "infer failed: $e")
                            respond(out, 500, """{"error":${jsonStr(e.toString())}}"""); return
                        }
                    val ms = System.currentTimeMillis() - t0
                    Log.i(TAG, "infer ok in ${ms}ms, ${text.length} chars")
                    respond(out, 200, buildResponse(text, prompt, ms))
                }
                else -> respond(out, 404, """{"error":"not_found"}""")
            }
        }
    }

    private fun readHead(ins: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = ins.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            sb.append(c.toChar())
            if (prev == '\r'.code && c == '\n'.code && sb.endsWith("\r\n\r\n")) break
            if (sb.length > 64 * 1024) return null
            prev = c
        }
        return sb.toString()
    }

    private fun readExactly(ins: InputStream, len: Int): String {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = ins.read(buf, off, len - off)
            if (r <= 0) break
            off += r
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val h = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    // ---------- 推理 ----------

    @Synchronized
    private fun ensureEngine(ctx: Context): Any? {
        engine?.let { return it }
        val libDir = ctx.applicationInfo.nativeLibraryDir
        val model = pickModel() ?: run { initErr = "no .litertlm under $MODEL_DIR"; return null }
        modelName = model.name
        Log.i(TAG, "initializing engine with ${model.name} (${model.length() / 1_000_000} MB)")
        return runCatching {
            val backendCls = Class.forName("com.google.ai.edge.litertlm.Backend")
            val npuCls = Class.forName("com.google.ai.edge.litertlm.Backend\$NPU")
            val cfgCls = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
            val engCls = Class.forName("com.google.ai.edge.litertlm.Engine")
            val npu = npuCls.getConstructor(String::class.java).newInstance(libDir)
            val cfg = cfgCls
                .getConstructor(String::class.java, backendCls, backendCls, backendCls, Integer::class.java)
                .newInstance(model.absolutePath, npu, null, null, Integer.valueOf(MAX_NUM_TOKENS))
            val eng = engCls.getConstructor(cfgCls).newInstance(cfg)
            engCls.getMethod("initialize").invoke(eng)
            val convCfgCls = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
            val convCfg = convCfgCls.getDeclaredConstructor().newInstance()
            val conv = engCls.methods.first { it.name == "createConversation" && it.parameterTypes.size == 1 }
                .invoke(eng, convCfg)
            val send = conv.javaClass.methods.first {
                it.name == "sendMessage" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java && Map::class.java.isAssignableFrom(it.parameterTypes[1])
            }
            convCls = conv.javaClass
            sendMethod = send
            conversation = conv
            engine = eng
            Log.i(TAG, "engine ready (NPU)")
            eng
        }.onFailure { initErr = it.toString(); Log.e(TAG, "engine init failed: $it") }.getOrNull()
    }

    private fun infer(prompt: String): String {
        val conv = conversation ?: error("conversation not ready")
        val send = sendMethod ?: error("sendMessage not resolved")
        val msg = send.invoke(conv, prompt, emptyMap<String, Any>()) ?: error("null message")
        return runCatching { msg.javaClass.getMethod("getText").invoke(msg) as String }
            .getOrElse { msg.toString() }
    }

    private fun pickModel(): File? {
        val dir = File(MODEL_DIR)
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.length() > 100L * 1024 * 1024 }
            ?.sortedBy { it.length() } ?: return null
        // 已知坏档（Qwen3.5 的 _mm_ 多模态导出：文本路径损坏）排后
        return all.firstOrNull { it.name == PREFERRED } ?: all.firstOrNull { !it.name.contains("_mm_") } ?: all.first()
    }

    // ---------- 极简 JSON ----------

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append('"').toString()
    }

    /** 取最后一条 role=user 的 content（够用即可，不做完整 JSON 解析）。 */
    private fun extractLastUserContent(body: String): String {
        var last = ""
        var idx = 0
        while (true) {
            val u = body.indexOf("\"user\"", idx).takeIf { it >= 0 } ?: break
            val c = body.indexOf("\"content\"", u).takeIf { it >= 0 } ?: break
            val colon = body.indexOf(':', c)
            var i = colon + 1
            while (i < body.length && body[i].isWhitespace()) i++
            if (i < body.length && body[i] == '"') {
                val sb = StringBuilder(); i++
                while (i < body.length) {
                    val ch = body[i]
                    if (ch == '\\' && i + 1 < body.length) { sb.append(body[i + 1]); i += 2; continue }
                    if (ch == '"') break
                    sb.append(ch); i++
                }
                last = sb.toString()
            }
            idx = u + 6
        }
        return last
    }

    private fun buildResponse(text: String, prompt: String, ms: Long): String {
        val created = System.currentTimeMillis() / 1000
        return """{"id":"chatcmpl-local-${System.nanoTime()}","object":"chat.completion","created":$created,""" +
            """"model":${jsonStr(modelName)},""" +
            """"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":${jsonStr(text)}}}],""" +
            """"usage":{"prompt_tokens":${estimateTokens(prompt)},"completion_tokens":${estimateTokens(text)},""" +
            """"total_tokens":${estimateTokens(prompt) + estimateTokens(text)}},""" +
            """"timings":{"wall_ms":$ms,"backend":"NPU"}}"""
    }

    /** 粗略估算 token 数（中英混排约 3 字符/token），避免额外依赖分词器。 */
    private fun estimateTokens(s: String): Int = (s.length / 3).coerceAtLeast(1)
}
