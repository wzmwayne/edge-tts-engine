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
        EdgeLog.syncFromSettings(applicationContext)
        EdgeLog.d(TAG, "service onCreate")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!VoiceStore.isLanguageSupported(this, lang)) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        // 必须精确匹配 getExpectedLanguageAvailableStatus：
        // variant 空 + country 空 -> LANG_AVAILABLE；variant 空 -> LANG_COUNTRY_AVAILABLE；否则 VAR_AVAILABLE
        return when {
            variant.isNullOrEmpty() && country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
            variant.isNullOrEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (VoiceStore.voiceList(this).any { it.shortName == voiceName })
            TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int =
        if (VoiceStore.voiceList(this).any { it.shortName == voiceName })
            TextToSpeech.SUCCESS else TextToSpeech.ERROR

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
        val t0 = System.currentTimeMillis()
        try {
            val text = request.charSequenceText?.toString() ?: ""
            EdgeLog.d(
                TAG,
                "onSynthesizeText text=${text.take(80)} voiceName=${request.voiceName} " +
                    "rate=${request.speechRate} pitch=${request.pitch}",
            )
            if (text.isEmpty()) {
                EdgeLog.w(TAG, "empty text -> callback.error()")
                callback.error()
                return
            }

            // 音色回退链：request.voiceName -> 上次选中 -> 默认
            var voice = request.voiceName?.takeIf { it.isNotEmpty() }
            val fallbackSource = if (voice != null) "request.voiceName" else null
            if (voice == null) {
                voice = VoiceStore.selectedVoice(this)
                EdgeLog.d(TAG, "voiceName 为空，回退到已选音色 voice=${voice ?: "null"}")
            }
            if (voice == null) {
                voice = "zh-CN-XiaoxiaoNeural"
                EdgeLog.d(TAG, "已选音色也为空，回退到默认 voice=$voice")
            }
            EdgeLog.d(TAG, "effective voice=$voice source=$fallbackSource")

            // speechRate/pitch: 100 为正常，范围约 50~200
            val rate = request.speechRate - 100
            val pitch = request.pitch - 100
            EdgeLog.d(TAG, "computed rate=$rate pitch=$pitch")

            EdgeLog.d(TAG, "开始请求 Edge TTS 合成音频...")

            if (VoiceStore.streaming(this)) {
                synthesizeStreaming(text, voice, rate, pitch, callback, t0)
            } else {
                synthesizeBuffered(text, voice, rate, pitch, callback, t0)
            }
        } catch (e: Exception) {
            EdgeLog.e(TAG, "synthesize failed, took=${System.currentTimeMillis() - t0}ms", e)
            callback.error()
        }
    }

    /** 全量缓冲路径：收完 MP3 -> 整体解码 -> 喂给回调。 */
    private fun synthesizeBuffered(
        text: String,
        voice: String,
        rate: Int,
        pitch: Int,
        callback: SynthesisCallback,
        t0: Long,
    ) {
        val mp3 = EdgeWs.collectMp3(text, voice, rate, pitch) { stopped }
        if (stopped || mp3.isEmpty()) {
            EdgeLog.w(
                TAG,
                "stopped=$stopped mp3.size=${mp3.size} -> callback.error() " +
                    "took=${System.currentTimeMillis() - t0}ms",
            )
            callback.error()
            return
        }
        EdgeLog.d(TAG, "mp3 obtained size=${mp3.size} took=${System.currentTimeMillis() - t0}ms")

        val pcm = Mp3Decoder.decode(mp3)
        if (stopped) {
            EdgeLog.w(TAG, "stopped during decode -> callback.error()")
            callback.error()
            return
        }
        EdgeLog.d(
            TAG,
            "decode done pcm=${pcm.data.size}B sr=${pcm.sampleRate} ch=${pcm.channels}",
        )

        callback.start(pcm.sampleRate, AudioFormat.ENCODING_PCM_16BIT, pcm.channels)
        val max = callback.maxBufferSize
        var off = 0
        var fed = 0
        EdgeLog.d(TAG, "callback.start ok maxBufferSize=$max, 开始喂数据...")
        while (off < pcm.data.size && !stopped) {
            val n = minOf(max, pcm.data.size - off)
            callback.audioAvailable(pcm.data, off, n)
            off += n
            fed += n
            if (off % (max * 20) == 0) {
                EdgeLog.d(TAG, "feeding progress ${off}/${pcm.data.size} fed=$fed")
            }
        }
        EdgeLog.d(TAG, "fed $fed/${pcm.data.size} stopped=$stopped")
        if (stopped) callback.error() else callback.done()
        EdgeLog.d(
            TAG,
            "onSynthesizeText 完成 fed=$fed total_took=${System.currentTimeMillis() - t0}ms",
        )
    }

    /** 流式路径：边收 MP3 帧边解码边喂给回调，首帧到达即开始输出。 */
    private fun synthesizeStreaming(
        text: String,
        voice: String,
        rate: Int,
        pitch: Int,
        callback: SynthesisCallback,
        t0: Long,
    ) {
        val splitter = Mp3FrameSplitter()
        var fed = 0L

        val decoder = StreamingDecoder(
            onStart = { sampleRate, channels ->
                callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channels)
                EdgeLog.d(TAG, "streaming callback.start sr=$sampleRate ch=$channels")
            },
            onPcm = { pcm ->
                if (stopped) return@StreamingDecoder
                val max = callback.maxBufferSize
                var off = 0
                while (off < pcm.size && !stopped) {
                    val n = minOf(max, pcm.size - off)
                    callback.audioAvailable(pcm, off, n)
                    off += n
                    fed += n
                }
            },
        )

        try {
            EdgeWs.synthesize(
                text, voice, rate, pitch,
                isStopped = { stopped },
                onAudio = { audioChunk ->
                    if (stopped) return@onAudio
                    for (frame in splitter.push(audioChunk)) {
                        if (stopped) return@onAudio
                        if (!decoder.isStarted) {
                            val h = Mp3Frame.parseHeader(frame, 0, frame.size)
                            if (h != null) {
                                decoder.startFrom(h)
                                decoder.push(frame, h.samplesPerFrame)
                            }
                        } else {
                            val h = Mp3Frame.parseHeader(frame, 0, frame.size)
                            if (h != null) decoder.push(frame, h.samplesPerFrame)
                        }
                    }
                },
                onChunkStart = { splitter.reset() },
            )
            if (stopped) {
                EdgeLog.w(TAG, "streaming stopped mid-synthesis -> error")
                callback.error()
                return
            }
            if (!decoder.isStarted) {
                EdgeLog.w(TAG, "streaming no audio frames received -> error")
                callback.error()
                return
            }
            decoder.finish()
            if (stopped) {
                EdgeLog.w(TAG, "streaming stopped during drain -> error")
                callback.error()
            } else {
                EdgeLog.d(
                    TAG,
                    "streaming done fed=$fed took=${System.currentTimeMillis() - t0}ms",
                )
                callback.done()
            }
        } catch (e: Exception) {
            EdgeLog.e(TAG, "streaming synthesize failed, took=${System.currentTimeMillis() - t0}ms", e)
            callback.error()
        }
    }
}