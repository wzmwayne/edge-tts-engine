package com.edge.tts.edge_tts_engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 响应系统 TTS 设置界面的 ACTION_CHECK_TTS_DATA 数据完整性检查。
 * 系统只有在收到非空返回数据后，才会把本引擎写入 TTS_DEFAULT_SYNTH（默认引擎）。
 * 缺失该 Activity 会导致「设为默认后系统强制切回其他引擎」。
 */
class VoiceCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeLog.init(applicationContext)
        EdgeLog.d("VoiceCheck", "ACTION_CHECK_TTS_DATA 收到，返回可用音色列表")
        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()
        try {
            for (v in VoiceStore.voiceList(this)) {
                val loc = v.localeObj() ?: continue
                available.add(localeString(loc))
            }
        } catch (e: Exception) {
            EdgeLog.e("VoiceCheck", "构建音色列表失败", e)
        }
        val data = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                available,
            )
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                unavailable,
            )
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data)
        finish()
    }

    /** 系统期望的格式：3 位语言码[-3 位国家码[-variant]]，如 zho-CHN。 */
    private fun localeString(loc: Locale): String {
        val lang = try {
            loc.getISO3Language()
        } catch (e: Exception) {
            loc.language
        }
        val sb = StringBuilder(lang)
        if (loc.country.isNotEmpty()) {
            sb.append('-').append(
                try {
                    loc.getISO3Country()
                } catch (e: Exception) {
                    loc.country
                }
            )
        }
        if (loc.variant.isNotEmpty()) sb.append('-').append(loc.variant)
        return sb.toString()
    }
}