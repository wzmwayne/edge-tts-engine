package com.edge.tts.edge_tts_engine

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 内嵌 HTTP 中转服务器：供 Legado 等阅读 App 通过 http://127.0.0.1:10881 调用。
 *
 * 与 Python 原型 edge_tts_server.py 行为一致：
 *  - GET 查询参数 / POST 请求体（form 或 JSON）均可
 *  - 文本参数：tex（百度风格，双重编码）优先，其次 text，再退到原始请求体
 *  - 剥掉 Legado 追加的字面 "=" 结尾
 *  - 音色/语速/音调优先取请求参数，否则用应用内设置（VoiceStore）
 *  - 流式开关（VoiceStore.streaming）决定 chunked 流式 or 全量缓冲响应
 */
object HttpTtsServer {
    private const val TAG = "HttpTtsServer"
    private const val PORT = 10881
    private const val DEFAULT_VOICE = "zh-CN-YunyangNeural"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val lock = Any()

    fun start(context: Context) {
        synchronized(lock) {
            if (serverSocket != null) return
            val ctx = context.applicationContext
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", PORT))
            } catch (e: Exception) {
                EdgeLog.e(TAG, "bind 失败", e)
                try {
                    ss.close()
                } catch (ignored: Exception) {
                }
                return
            }
            serverSocket = ss
            acceptThread = Thread({
                while (!ss.isClosed) {
                    val sock = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    Thread({
                        handle(sock, ctx)
                    }).apply {
                        isDaemon = true
                        start()
                    }
                }
            }).apply {
                isDaemon = true
                name = "edge-http-server"
                start()
            }
            EdgeLog.d(TAG, "HTTP server listening on 127.0.0.1:$PORT")
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                serverSocket?.close()
            } catch (ignored: Exception) {
            }
            serverSocket = null
            acceptThread = null
            EdgeLog.d(TAG, "HTTP server stopped")
        }
    }

    private fun handle(sock: Socket, ctx: Context) {
        try {
            sock.soTimeout = 60000
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()

            val requestLine = readLine(inp) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "" }
            val target = parts.getOrElse(1) { "/" }

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val params = mutableMapOf<String, String>()
            var rawBody = ""
            if (method == "GET") {
                val q = target.indexOf('?')
                if (q >= 0) parseForm(target.substring(q + 1), params)
            } else {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = inp.read(body, off, len - off)
                    if (r < 0) break
                    off += r
                }
                val bodyStr = String(body, Charsets.UTF_8)
                val ctype = headers["content-type"] ?: ""
                if (ctype.contains("json")) {
                    try {
                        val obj = JSONObject(bodyStr)
                        val it = obj.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            params[k] = obj.optString(k)
                        }
                    } catch (e: Exception) {
                        EdgeLog.e(TAG, "json body 解析失败", e)
                    }
                } else {
                    val parsed = HashMap<String, String>()
                    parseForm(bodyStr, parsed)
                    if (parsed.isNotEmpty()) params.putAll(parsed)
                    rawBody = bodyStr
                }
            }

            val text = resolveText(params, rawBody)
            if (text.isEmpty()) {
                sendJson(out, 400, "{\"error\":\"missing tex/text param\"}")
                return
            }

            val cfg = VoiceStore.readConfig(ctx)
            val voice = params["voice"]
                ?: (cfg["voice"] as? String)?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_VOICE

            val ratePercent = when {
                params.containsKey("rate") ->
                    (params["rate"]!!.toIntOrNull() ?: 50) - 50
                params.containsKey("spd") ->
                    ((params["spd"]!!.toIntOrNull() ?: 5) - 5) * 10
                else -> (cfg["rate"] as? Int ?: 100) - 100
            }
            val pitchHz = when {
                params.containsKey("pitch") ->
                    (params["pitch"]!!.toIntOrNull() ?: 50) - 50
                params.containsKey("pit") ->
                    ((params["pit"]!!.toIntOrNull() ?: 5) - 5) * 10
                else -> (cfg["pitch"] as? Int ?: 100) - 100
            }

            EdgeLog.d(
                TAG,
                "请求 voice=$voice rate=${fmtSigned(ratePercent, "%")} " +
                    "pitch=${fmtSigned(pitchHz, "Hz")} text=${text.take(60)}",
            )

            if (VoiceStore.streaming(ctx)) {
                streamAudio(out, text, voice, ratePercent, pitchHz)
            } else {
                bufferedAudio(out, text, voice, ratePercent, pitchHz)
            }
        } catch (e: Exception) {
            EdgeLog.e(TAG, "handle 异常", e)
        } finally {
            try {
                sock.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /** 剥掉 Legado 追加的字面 "=" 结尾。 */
    private fun resolveText(params: Map<String, String>, rawBody: String): String {
        val text = when {
            params.containsKey("tex") && params["tex"]!!.isNotEmpty() ->
                unquoteUntilStable(params["tex"]!!)
            params.containsKey("text") && params["text"]!!.isNotEmpty() ->
                params["text"]!!
            else -> unquoteUntilStable(rawBody)
        }
        return if (text.endsWith("=")) text.dropLast(1) else text
    }

    private fun bufferedAudio(
        out: OutputStream,
        text: String,
        voice: String,
        ratePercent: Int,
        pitchHz: Int,
    ) {
        val mp3 = EdgeWs.collectMp3(text, voice, ratePercent, pitchHz) { false }
        if (mp3.isEmpty()) {
            sendJson(out, 500, "{\"error\":\"no audio\"}")
            return
        }
        val head =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/mpeg\r\n" +
                "Content-Length: ${mp3.size}\r\n" +
                "Cache-Control: no-store\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(mp3)
        out.flush()
        EdgeLog.d(TAG, "buffered ok size=${mp3.size}")
    }

    private fun streamAudio(
        out: OutputStream,
        text: String,
        voice: String,
        ratePercent: Int,
        pitchHz: Int,
    ) {
        val head =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/mpeg\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Cache-Control: no-store\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        var total = 0L
        try {
            EdgeWs.synthesize(
                text, voice, ratePercent, pitchHz,
                isStopped = { false },
                onAudio = { chunk ->
                    if (chunk.isNotEmpty()) {
                        val n = chunk.size
                        out.write(Integer.toHexString(n).toByteArray(Charsets.ISO_8859_1))
                        out.write('\r'.toInt())
                        out.write('\n'.toInt())
                        out.write(chunk)
                        out.write('\r'.toInt())
                        out.write('\n'.toInt())
                        out.flush()
                        total += n
                    }
                },
            )
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: Exception) {
            EdgeLog.e(TAG, "streaming 中断", e)
        }
        EdgeLog.d(TAG, "streamed ok size=$total")
    }

    private fun sendJson(out: OutputStream, status: Int, body: String) {
        val data = body.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 $status ${statusText(status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Cache-Control: no-store\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        400 -> "Bad Request"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun parseForm(query: String, out: MutableMap<String, String>) {
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx < 0) {
                out[pair] = ""
            } else {
                out[pair.substring(0, idx)] =
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
        }
    }

    private fun unquoteUntilStable(s: String): String {
        var prev: String? = null
        var cur = s
        while (cur != prev) {
            prev = cur
            cur = try {
                URLDecoder.decode(cur, "UTF-8")
            } catch (e: Exception) {
                cur
            }
        }
        return cur
    }

    private fun readLine(inp: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var b = inp.read()
        while (b >= 0 && b != '\n'.toInt()) {
            if (b != '\r'.toInt()) buf.write(b)
            b = inp.read()
        }
        return if (b < 0 && buf.size() == 0) null else buf.toString("UTF-8")
    }

    private fun fmtSigned(v: Int, unit: String): String {
        if (v == 0) return "+0$unit"
        return (if (v > 0) "+" else "") + v + unit
    }
}