package com.edge.tts.edge_tts_engine

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * 与微软 Edge 在线 TTS 服务通信的原始 WebSocket 客户端（零第三方依赖）。
 * 协议细节与社区 edge-tts 项目一致。
 */
object EdgeWs {
    private const val TAG = "EdgeWs"
    private const val WSS_HOST = "speech.platform.bing.com"
    private const val WSS_PATH =
        "/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11644473600L
    private const val CHROMIUM = "143"
    private const val MAX_TEXT_BYTES = 4096
    private val RAND = SecureRandom()

    fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

    fun dateStr(): String {
        val df = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        )
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

    fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + WIN_EPOCH
        ticks -= ticks % 300
        ticks *= 10_000_000L
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(it) }
    }

    private fun escapeXml(s: String): String = buildString {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                ch.code in 0..8 || ch.code in 11..12 || ch.code in 14..31 -> append(' ')
                else -> append(ch)
            }
        }
    }

    private fun splitChunks(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (ch in text) {
            val candidate = cur.toString() + ch
            if (cur.isNotEmpty() && candidate.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
                chunks.add(cur.toString())
                cur.setLength(0)
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks
    }

    fun mkssml(voice: String, rate: String, pitch: String, text: String): String {
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'" +
            " xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
            text +
            "</prosody></voice></speak>"
    }

    /**
     * 合成文本，返回完整 MP3 字节流。长文本按 4096 字节分块，每块单独连接。
     */
    fun collectMp3(
        text: String,
        voice: String,
        ratePercent: Int,
        pitchHz: Int,
        isStopped: () -> Boolean,
    ): ByteArray {
        val t0 = System.currentTimeMillis()
        val rate = (if (ratePercent == 0) "+0%" else "${if (ratePercent > 0) "+" else ""}$ratePercent%")
        val pitch = (if (pitchHz == 0) "+0Hz" else "${if (pitchHz > 0) "+" else ""}$pitchHz")
        EdgeLog.d(TAG, "collectMp3 text=${text.take(80)} voice=$voice rate=$rate pitch=$pitch")
        val out = ByteArrayOutputStream()
        for (chunk in splitChunks(text)) {
            if (isStopped()) break
            EdgeLog.d(TAG, "chunk start bytes=${chunk.toByteArray(Charsets.UTF_8).size}")
            val sock = connect()
            try {
                val cfg =
                    "X-Timestamp:${dateStr()}\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                        "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"" +
                        "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                sendFrame(sock, 0x1, cfg.toByteArray(Charsets.UTF_8))

                val ssml = mkssml(voice, rate, pitch, escapeXml(chunk))
                val req =
                    "X-RequestId:${connectId()}\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${dateStr()}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssml
                sendFrame(sock, 0x1, req.toByteArray(Charsets.UTF_8))

                while (true) {
                    if (isStopped()) return out.toByteArray()
                    val frame = readFrame(sock)
                    when (frame.opcode) {
                        0x8 -> throw IOException("server closed ws")
                        0x9 -> sendFrame(sock, 0xA, frame.payload) // ping -> pong
                        0xA -> {}
                        0x1 -> if (String(frame.payload, Charsets.UTF_8).contains("Path:turn.end")) {
                            EdgeLog.d(TAG, "turn.end received, out=${out.size()}B")
                            break
                        }
                        0x2 -> parseAudio(frame.payload)?.let {
                            out.write(it)
                            EdgeLog.d(TAG, "audio chunk +${it.size}B total=${out.size()}B")
                        }
                    }
                }
            } finally {
                try {
                    sock.close()
                } catch (ignored: Exception) {
                }
            }
        }
        EdgeLog.d(TAG, "collectMp3 done total=${out.size()}B took=${System.currentTimeMillis() - t0}ms")
        return out.toByteArray()
    }

    /** 解析二进制音频帧：前 2 字节为头长度，头为文本，其后为 MP3 数据。 */
    private fun parseAudio(payload: ByteArray): ByteArray? {
        if (payload.size < 2) return null
        val headerLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (headerLen > payload.size) return null
        val header = String(payload, 2, headerLen, Charsets.ISO_8859_1)
        if (!header.startsWith("Path:audio")) return null
        return payload.copyOfRange(2 + headerLen, payload.size)
    }

    private fun connect(): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(WSS_HOST, 443), 15000)
            raw.tcpNoDelay = true
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val ssl = ctx.socketFactory.createSocket(raw, WSS_HOST, 443, true) as SSLSocket
            ssl.soTimeout = 60000
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(WSS_HOST))
            ssl.sslParameters = params
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(WSS_HOST, ssl.session)) {
                throw IOException("TLS hostname mismatch")
            }
            EdgeLog.d(TAG, "TLS handshake ok")

            val query =
                "&ConnectionId=${connectId()}" +
                    "&Sec-MS-GEC=${secMsGec()}" +
                    "&Sec-MS-GEC-Version=1-${CHROMIUM}.0.3650.75"
            val key = Base64.encodeToString(ByteArray(16).also { RAND.nextBytes(it) }, Base64.NO_WRAP)
            val req =
                "GET $WSS_PATH$query HTTP/1.1\r\n" +
                    "Host: $WSS_HOST\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n" +
                    "User-Agent: ${EdgeTtsEngineService.UA}\r\n" +
                    "Cookie: muid=${connectId().uppercase()};\r\n" +
                    "\r\n"
            ssl.outputStream.write(req.toByteArray(Charsets.UTF_8))
            ssl.outputStream.flush()

            val buf = ByteArray(4096)
            val acc = ByteArrayOutputStream()
            var timeoutMs = System.currentTimeMillis() + 20000
            while (acc.toString("ISO-8859-1").indexOf("\r\n\r\n") < 0) {
                if (System.currentTimeMillis() > timeoutMs) throw IOException("handshake timeout")
                val n = ssl.inputStream.read(buf)
                if (n < 0) throw EOFException("connection closed during handshake")
                acc.write(buf, 0, n)
            }
            val head = acc.toString("ISO-8859-1")
            if (head.indexOf(" 101 ") < 0) {
                throw IOException("ws handshake failed: ${head.split("\r\n")[0]}")
            }
            EdgeLog.d(TAG, "ws handshake ok ${head.split("\r\n")[0]}")
            return ssl
        } catch (e: Exception) {
            EdgeLog.e(TAG, "connect failed", e)
            try {
                raw.close()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    private fun sendFrame(sock: SSLSocket, opcode: Int, payload: ByteArray) {
        val out: OutputStream = sock.outputStream
        val mask = ByteArray(4).also { RAND.nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> header.write(0x80 or n)
            n < 65536 -> {
                header.write(0x80 or 126)
                header.write((n shr 8) and 0xFF)
                header.write(n and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (i in 7 downTo 0) header.write(((n.toLong() shr (i * 8)) and 0xFF).toInt())
            }
        }
        header.write(mask)
        out.write(header.toByteArray())
        for (i in payload.indices) {
            out.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
        }
        out.flush()
    }

    private class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(sock: SSLSocket): Frame {
        val inp: InputStream = sock.inputStream
        val b1 = inp.read()
        val b2 = inp.read()
        if (b1 < 0 || b2 < 0) throw EOFException("connection closed")
        val opcode = b1 and 0x0F
        var n = (b2 and 0x7F)
        if (n == 126) {
            val hi = inp.read()
            val lo = inp.read()
            if (hi < 0 || lo < 0) throw EOFException("connection closed")
            n = (hi shl 8) or lo
        } else if (n == 127) {
            var len = 0L
            for (i in 0 until 8) {
                val b = inp.read()
                if (b < 0) throw EOFException("connection closed")
                len = (len shl 8) or b.toLong()
            }
            n = len.toInt()
        }
        var mask: ByteArray? = null
        if ((b2 and 0x80) != 0) {
            mask = ByteArray(4)
            var i = 0
            while (i < 4) {
                val b = inp.read()
                if (b < 0) throw EOFException("connection closed")
                mask[i] = b.toByte()
                i++
            }
        }
        val payload = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(payload, off, n - off)
            if (r < 0) throw EOFException("connection closed")
            off += r
        }
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(opcode, payload)
    }
}