package com.edge.tts.edge_tts_engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 用系统 MediaExtractor + MediaCodec 把 MP3 解码为 PCM。
 */
object Mp3Decoder {

    class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

    fun decode(mp3: ByteArray): Pcm {
        val tmp = File.createTempFile("edge_tts", ".mp3")
        try {
            tmp.writeBytes(mp3)
            return decodeFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun decodeFile(file: File): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i
                format = f
                break
            }
        }
        if (trackIdx < 0 || format == null) throw IOException("no audio track")
        extractor.selectTrack(trackIdx)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val sampleRate = try {
                codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } catch (e: Exception) {
                24000
            }
            val channels = try {
                codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } catch (e: Exception) {
                1
            }

            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)
                        val size = extractor.readSampleData(buf!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> {
                        if (outIdx >= 0) {
                            if (info.size > 0) {
                                val out = codec.getOutputBuffer(outIdx) ?: continue
                                val arr = ByteArray(info.size)
                                out.get(arr)
                                pcm.write(arr)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
            }
            return Pcm(pcm.toByteArray(), sampleRate, channels)
        } finally {
            try {
                codec.stop()
            } catch (ignored: Exception) {
            }
            try {
                codec.release()
            } catch (ignored: Exception) {
            }
            try {
                extractor.release()
            } catch (ignored: Exception) {
            }
        }
    }
}