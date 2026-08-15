package com.edge.tts.edge_tts_engine

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从微软 voices 接口拉取全部音色 JSON。
 */
object VoiceFetcher {
    private const val VOICE_LIST_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/" +
            "voices/list?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    fun fetch(): String {
        val conn = URL(VOICE_LIST_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", EdgeTtsEngineService.UA)
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(stream.reader()).use { it.readText() }
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }
}