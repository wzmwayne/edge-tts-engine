package com.edge.tts.edge_tts_engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice

/**
 * 系统级 TTS 引擎：把微软 Edge 在线语音合成接入 Android TextToSpeech。
 * 任意支持系统 TTS 的应用（如 Legado 阅读器）安装本引擎并设为默认后即可使用。
 */
class EdgeTtsEngineService : TextToSpeechService() {
    companion object {
        private const val TAG = "EdgeTtsEngine"
        private const val CHROMIUM = "143"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                " (KHTML, like Gecko) Chrome/$CHROMIUM.0.0.0 Safari/537.36" +
                " Edg/$CHROMIUM.0.0.0"
    }

    @Volatile
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        EdgeLog.init(applicationContext)
        EdgeLog.d(TAG, "service onCreate")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE

    override fun onGetLanguage(): Array<String> = arrayOf("zho", "CHN", "")

    override fun onGetVoices(): List<Voice> = VoiceStore.voices(this)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        return VoiceStore.defaultVoiceFor(this, lang, country)
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        try {
            val text = request.charSequenceText?.toString() ?: ""
            EdgeLog.d(
                TAG,
                "onSynthesizeText text=${text.take(80)} voice=${request.voiceName} " +
                    "rate=${request.speechRate} pitch=${request.pitch}",
            )
            if (text.isEmpty()) {
                EdgeLog.w(TAG, "empty text, error")
                callback.error()
                return
            }
            val voice = request.voiceName?.takeIf { it.isNotEmpty() }
                ?: VoiceStore.selectedVoice(this)
                ?: "zh-CN-XiaoxiaoNeural"
            EdgeLog.d(TAG, "effective voice=$voice")

            // speechRate/pitch: 100 为正常，范围约 50~200
            val rate = request.speechRate - 100
            val pitch = request.pitch - 100

            val mp3 = EdgeWs.collectMp3(text, voice, rate, pitch) { stopped }
            if (stopped || mp3.isEmpty()) {
                EdgeLog.w(TAG, "stopped=${stopped} mp3.size=${mp3.size}, error")
                callback.error()
                return
            }
            EdgeLog.d(TAG, "mp3 obtained size=${mp3.size}")

            val pcm = Mp3Decoder.decode(mp3)
            if (stopped) {
                EdgeLog.w(TAG, "stopped during decode, error")
                callback.error()
                return
            }

            callback.start(pcm.sampleRate, AudioFormat.ENCODING_PCM_16BIT, pcm.channels)
            val max = callback.maxBufferSize
            var off = 0
            var fed = 0
            while (off < pcm.data.size && !stopped) {
                val n = minOf(max, pcm.data.size - off)
                callback.audioAvailable(pcm.data, off, n)
                off += n
                fed += n
            }
            EdgeLog.d(TAG, "fed $fed/${pcm.data.size} stopped=$stopped")
            if (stopped) callback.error() else callback.done()
        } catch (e: Exception) {
            EdgeLog.e(TAG, "synthesize failed", e)
            callback.error()
        }
    }
}