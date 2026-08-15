package com.edge.tts.edge_tts_engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.json.JSONArray
import java.util.Locale

/**
 * 音色与配置的本地存储。UI（Flutter）通过 MethodChannel 读写这里，
 * 引擎服务（EdgeTtsEngineService）在合成时读取当前音色。
 */
object VoiceStore {
    private const val KEY_VOICES_JSON = "voices_json"
    private const val KEY_VOICE = "selected_voice"
    private const val KEY_RATE = "rate"
    private const val KEY_PITCH = "pitch"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("edge_tts", Context.MODE_PRIVATE)

    fun writeConfig(c: Context, voice: String?, rate: Int, pitch: Int) {
        prefs(c).edit()
            .putString(KEY_VOICE, voice)
            .putInt(KEY_RATE, rate)
            .putInt(KEY_PITCH, pitch)
            .apply()
    }

    fun readConfig(c: Context): Map<String, Any> = mapOf(
        "voice" to (prefs(c).getString(KEY_VOICE, null) ?: "zh-CN-XiaoxiaoNeural"),
        "rate" to prefs(c).getInt(KEY_RATE, 100),
        "pitch" to prefs(c).getInt(KEY_PITCH, 100),
    )

    fun selectedVoice(c: Context): String? = prefs(c).getString(KEY_VOICE, null)

    fun saveVoicesJson(c: Context, json: String) {
        prefs(c).edit().putString(KEY_VOICES_JSON, json).apply()
    }

    fun voicesJson(c: Context): String {
        val stored = prefs(c).getString(KEY_VOICES_JSON, null)
        return stored?.takeIf { it.isNotBlank() } ?: FALLBACK_VOICES_JSON
    }

    fun voiceList(c: Context): List<VoiceItem> {
        return try {
            val arr = JSONArray(voicesJson(c))
            (0 until arr.length()).map { VoiceItem.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 系统要求的所有可用语言（3 位语言码）。 */
    fun supportedLanguages(c: Context): Set<String> {
        val out = mutableSetOf<String>()
        for (v in voiceList(c)) {
            val loc = v.localeObj() ?: continue
            try {
                loc.iso3Language?.let { out.add(it) }
            } catch (e: Exception) {
                out.add(loc.language)
            }
        }
        return out
    }

    /** 系统要求的 Voice 对象集合（API 21+）。 */
    fun supportedVoices(c: Context): Set<Voice> {
        val out = mutableSetOf<Voice>()
        for (v in voiceList(c)) {
            val loc = v.localeObj() ?: continue
            val builder = Voice.Builder(v.shortName, loc)
                .quality(Voice.QUALITY_VERY_HIGH)
                .latency(Voice.LATENCY_NORMAL)
                .requiresNetworkConnection(true)
                .addFeatures(setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS))
            out.add(builder.build())
        }
        return out
    }

    /** 根据语言/国家返回一个匹配的默认音色 ShortName。 */
    fun defaultVoiceFor(c: Context, lang: String?, country: String?): String? {
        val wanted = buildString {
            append(lang ?: "")
            if (!country.isNullOrEmpty()) {
                append("-").append(country)
            }
        }.lowercase()
        for (v in voiceList(c)) {
            val loc = v.localeObj() ?: continue
            val langPart = loc.language.lowercase()
            val countryPart = loc.country.lowercase()
            if (wanted.startsWith(langPart)) {
                if (country.isNullOrEmpty() || countryPart == country.lowercase()) {
                    return v.shortName
                }
            }
        }
        return voiceList(c).firstOrNull()?.shortName
    }

    data class VoiceItem(
        val shortName: String,
        val locale: String,
        val gender: String,
        val friendlyName: String,
    ) {
        fun localeObj(): Locale? {
            return try {
                Locale.forLanguageTag(locale)
            } catch (e: Exception) {
                null
            }
        }

        companion object {
            fun fromJson(o: org.json.JSONObject): VoiceItem {
                return VoiceItem(
                    shortName = o.optString("ShortName", ""),
                    locale = o.optString("Locale", ""),
                    gender = o.optString("Gender", ""),
                    friendlyName = o.optString("FriendlyName", ""),
                )
            }
        }
    }

    /** 网络拉取失败时的内置回退音色列表。 */
    private val FALLBACK_VOICES = listOf(
        "zh-CN-XiaoxiaoNeural" to "zh-CN",
        "zh-CN-XiaoyiNeural" to "zh-CN",
        "zh-CN-YunxiNeural" to "zh-CN",
        "zh-CN-YunjianNeural" to "zh-CN",
        "zh-CN-YunyangNeural" to "zh-CN",
        "zh-CN-YunxiaNeural" to "zh-CN",
        "zh-CN-liaoning-XiaobeiNeural" to "zh-CN-liaoning",
        "zh-CN-shaanxi-XiaoniNeural" to "zh-CN-shaanxi",
        "zh-HK-HiuGaaiNeural" to "zh-HK",
        "zh-HK-HiuMaanNeural" to "zh-HK",
        "zh-HK-WanLungNeural" to "zh-HK",
        "zh-TW-HsiaoChenNeural" to "zh-TW",
        "zh-TW-HsiaoYuNeural" to "zh-TW",
        "zh-TW-YunJheNeural" to "zh-TW",
        "en-US-AriaNeural" to "en-US",
        "en-US-AvaNeural" to "en-US",
        "en-US-EmmaMultilingualNeural" to "en-US",
        "en-US-GuyNeural" to "en-US",
        "en-US-JennyNeural" to "en-US",
        "en-GB-SoniaNeural" to "en-GB",
        "en-GB-RyanNeural" to "en-GB",
        "ja-JP-NanamiNeural" to "ja-JP",
        "ja-JP-KeitaNeural" to "ja-JP",
        "ko-KR-SunHiNeural" to "ko-KR",
        "ko-KR-InJoonNeural" to "ko-KR",
        "fr-FR-DeniseNeural" to "fr-FR",
        "de-DE-KatjaNeural" to "de-DE",
        "es-ES-ElviraNeural" to "es-ES",
        "ru-RU-SvetlanaNeural" to "ru-RU",
    )

    private val FALLBACK_VOICES_JSON: String by lazy {
        val arr = JSONArray()
        val genders = mapOf(
            "Xiaoxiao" to "Female", "Xiaoyi" to "Female", "Yunxi" to "Male",
            "Yunjian" to "Male", "Yunyang" to "Male", "Yunxia" to "Male",
            "Xiaobei" to "Female", "Xiaoni" to "Female", "HiuGaai" to "Female",
            "HiuMaan" to "Female", "WanLung" to "Male", "HsiaoChen" to "Female",
            "HsiaoYu" to "Female", "YunJhe" to "Male", "Aria" to "Female",
            "Ava" to "Female", "Emma" to "Female", "Guy" to "Male",
            "Jenny" to "Female", "Sonia" to "Female", "Ryan" to "Male",
            "Nanami" to "Female", "Keita" to "Male", "SunHi" to "Female",
            "InJoon" to "Male", "Denise" to "Female", "Katja" to "Female",
            "Elvira" to "Female", "Svetlana" to "Female",
        )
        for ((shortName, locale) in FALLBACK_VOICES) {
            val name = shortName.split("-").lastOrNull() ?: shortName
            arr.put(org.json.JSONObject().apply {
                put("ShortName", shortName)
                put("Locale", locale)
                put("Gender", genders[name] ?: "Female")
                put("FriendlyName", "$shortName (Fallback)")
            })
        }
        arr.toString()
    }
}