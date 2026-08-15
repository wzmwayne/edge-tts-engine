package com.edge.tts.edge_tts_engine

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL = "com.edge.tts/engine"
        private const val TAG = "EdgeTtsUi"
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
                        "refreshVoices" -> {
                            Thread {
                                try {
                                    val json = VoiceFetcher.fetch()
                                    VoiceStore.saveVoicesJson(this, json)
                                    runOnUiThread { result.success(json) }
                                } catch (e: Exception) {
                                    Log.e(TAG, "fetch voices failed", e)
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
        var tts: TextToSpeech? = null
        tts = TextToSpeech(applicationContext, { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.language = Locale.US
                engine.setOnUtteranceProgressListener(object :
                    android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        engine.shutdown()
                        runOnUiThread { result.success(true) }
                    }

                    override fun onError(utteranceId: String?) {
                        engine.shutdown()
                        runOnUiThread { result.error("speak", "朗读失败", null) }
                    }
                })
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edge_tts_test")
            } else {
                runOnUiThread { result.error("speak", "引擎初始化失败", null) }
            }
        }, applicationContext.packageName)
    }
}