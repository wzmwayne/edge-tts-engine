package com.edge.tts.edge_tts_engine

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL = "com.edge.tts/engine"
        private const val TAG = "EdgeTtsUi"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeLog.init(applicationContext)
        EdgeLog.d(TAG, "MainActivity onCreate")
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "getConfig" -> result.success(VoiceStore.readConfig(this))
                        "setConfig" -> {
                            val voice = call.argument<String>("voice")
                            val rate = call.argument<Int>("rate") ?: 100
                            val pitch = call.argument<Int>("pitch") ?: 100
                            VoiceStore.writeConfig(this, voice, rate, pitch)
                            result.success(true)
                        }
                        "getVoices" -> result.success(VoiceStore.voicesJson(this))
                        "enableLogging" -> result.success(EdgeLog.enable(this))
                        "logFatal" -> {
                            val msg = call.arguments as? String ?: ""
                            EdgeLog.e(TAG, "Flutter fatal: $msg")
                            result.success(true)
                        }
                        "refreshVoices" -> {
                            Thread {
                                try {
                                    val json = VoiceFetcher.fetch()
                                    VoiceStore.saveVoicesJson(this, json)
                                    EdgeLog.d(TAG, "refreshVoices ok len=${json.length}")
                                    runOnUiThread { result.success(json) }
                                } catch (e: Exception) {
                                    EdgeLog.e(TAG, "refreshVoices failed", e)
                                    runOnUiThread { result.error("fetch", e.message, null) }
                                }
                            }.start()
                        }
                        "speak" -> {
                            val text = call.argument<String>("text") ?: "你好，这是 Edge 语音合成测试。"
                            speakWithEngine(text, result)
                        }
                        "openTtsSettings" -> {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            result.success(true)
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    result.error("error", e.message, null)
                }
            }
    }

    /** 用本 App 自带的引擎直接朗读，用于端到端试听。 */
    private fun speakWithEngine(text: String, result: MethodChannel.Result) {
        EdgeLog.d(TAG, "speakWithEngine start text=${text.take(60)}")
        var tts: TextToSpeech? = null
        tts = TextToSpeech(applicationContext, { status ->
            val engine = tts
            EdgeLog.d(TAG, "tts init status=$status engine=$engine")
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.language = Locale.US
                engine.setOnUtteranceProgressListener(object :
                    android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        EdgeLog.d(TAG, "utterance onStart id=$utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        EdgeLog.d(TAG, "utterance onDone id=$utteranceId")
                        engine.shutdown()
                        runOnUiThread { result.success(true) }
                    }

                    override fun onError(utteranceId: String?) {
                        EdgeLog.w(TAG, "utterance onError id=$utteranceId")
                        engine.shutdown()
                        runOnUiThread { result.error("speak", "朗读失败", null) }
                    }
                })
                val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edge_tts_test")
                EdgeLog.d(TAG, "speak() returned code=$code")
                if (code == TextToSpeech.ERROR) {
                    engine.shutdown()
                    runOnUiThread { result.error("speak", "引擎拒绝播放", null) }
                }
            } else {
                EdgeLog.w(TAG, "tts init failed status=$status")
                runOnUiThread { result.error("speak", "引擎初始化失败", null) }
            }
        }, applicationContext.packageName)
    }
}