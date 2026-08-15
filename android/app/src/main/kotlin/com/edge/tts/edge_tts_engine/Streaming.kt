package com.edge.tts.edge_tts_engine

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream

/**
 * MP3 流式解码支持：把 WebSocket 收到的 MP3 帧按帧切分，
 * 逐帧喂给 MediaCodec 增量解码为 PCM，实现边收边播。
 */
object Mp3Frame {
    private const val TAG = "Mp3Frame"
    // MPEG1 Layer III 码率(kbps)，索引 1..14
    private val BITRATES_MPEG1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    // MPEG2 / MPEG2.5 Layer III 码率(kbps)，索引 1..14（与 MPEG1 不同！）
    private val BITRATES_MPEG2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private const val V_MPEG1 = 3
    private const val V_MPEG2 = 2
    private const val V_MPEG25 = 0

    class Header(
        val bitrateKbps: Int,
        val sampleRate: Int,
        val channels: Int,
        val frameSize: Int,
        val samplesPerFrame: Int,
    )

    /** 解析 buf[off..limit) 处的帧头；数据不足、同步无效或非 Layer III 返回 null。 */
    fun parseHeader(buf: ByteArray, off: Int, limit: Int): Header? {
        if (limit - off < 4) return null
        if ((buf[off].toInt() and 0xFF) != 0xFF) return null
        val b1 = buf[off + 1].toInt() and 0xFF
        if ((b1 and 0xE0) != 0xE0) return null
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        if (layerBits != 1) return null  // 仅支持 Layer III（Edge TTS 输出）
        if (versionBits == 1) return null
        val b2 = buf[off + 2].toInt() and 0xFF
        val b3 = buf[off + 3].toInt() and 0xFF
        val bitrateIdx = (b2 shr 4) and 0x0F
        val sampleIdx = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01
        if (bitrateIdx == 0 || bitrateIdx == 15) return null
        if (sampleIdx == 3) return null

        val bitrate = if (versionBits == V_MPEG1) {
            BITRATES_MPEG1[bitrateIdx]
        } else {
            BITRATES_MPEG2[bitrateIdx]
        }
        val sampleRate = when (versionBits) {
            V_MPEG1 -> when (sampleIdx) {
                0 -> 44100
                1 -> 48000
                else -> 32000
            }
            V_MPEG2 -> when (sampleIdx) {
                0 -> 22050
                1 -> 24000
                else -> 16000
            }
            else -> when (sampleIdx) {
                0 -> 11025
                1 -> 12000
                else -> 8000
            }
        }
        val channelMode = (b3 shr 6) and 0x03
        val channels = if (channelMode == 3) 1 else 2
        val samplesPerFrame = if (versionBits == V_MPEG1) 1152 else 576
        val coeff = if (versionBits == V_MPEG1) 144 else 72
        val frameSize = coeff * bitrate * 1000 / sampleRate + padding
        return Header(bitrate, sampleRate, channels, frameSize, samplesPerFrame)
    }
}

/**
 * 累积 WebSocket 音频字节，按 MP3 帧切分返回完整帧。
 * 跨 push 保留未完成的帧头/帧尾，供下一次拼接。
 */
class Mp3FrameSplitter {
    private val buffer = ByteArrayOutputStream()

    fun push(bytes: ByteArray): List<ByteArray> {
        buffer.write(bytes)
        val data = buffer.toByteArray()
        buffer.reset()
        val frames = mutableListOf<ByteArray>()
        val n = data.size
        var off = 0
        while (off < n) {
            var sync = -1
            var i = off
            while (i + 1 < n) {
                if ((data[i].toInt() and 0xFF) == 0xFF &&
                    (data[i + 1].toInt() and 0xE0) == 0xE0
                ) {
                    sync = i
                    break
                }
                i++
            }
            if (sync < 0) {
                if (n > 0 && (data[n - 1].toInt() and 0xFF) == 0xFF) {
                    buffer.write(data[n - 1].toInt())
                }
                break
            }
            val hdr = Mp3Frame.parseHeader(data, sync, n)
            if (hdr == null) {
                if (n - sync < 4) {
                    buffer.write(data, sync, n - sync)
                    break
                }
                off = sync + 1
                continue
            }
            val end = sync + hdr.frameSize
            if (end > n) {
                buffer.write(data, sync, n - sync)
                break
            }
            frames.add(data.copyOfRange(sync, end))
            off = end
        }
        return frames
    }

    /** 新文本块（新连接）开始时调用，丢弃上一块未完成的残帧。 */
    fun reset() {
        buffer.reset()
    }
}

/**
 * 增量 MP3 解码：逐帧喂入 MediaCodec，解码出的 PCM 立即回调。
 * 每帧按帧内采样数累计时间戳，保证输出顺序正确。
 */
class StreamingDecoder(
    private val onStart: (sampleRate: Int, channels: Int) -> Unit,
    private val onPcm: (pcm: ByteArray) -> Unit,
) {
    private const val TAG = "StreamingDecoder"
    private var codec: MediaCodec? = null
    private var sampleRate = 0
    private var channels = 0
    private var ptsUs = 0L
    private var failed = false

    val isStarted: Boolean get() = codec != null
    val isFailed: Boolean get() = failed

    /** 用第一帧头配置解码器并启动（此时即可通知系统开始输出）。 */
    fun startFrom(h: Mp3Frame.Header) {
        sampleRate = h.sampleRate
        channels = h.channels
        onStart(sampleRate, channels)
        val fmt = MediaFormat()
        fmt.setString(MediaFormat.KEY_MIME, "audio/mpeg")
        fmt.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
        fmt.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
        try {
            val c = MediaCodec.createDecoderByType("audio/mpeg")
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            EdgeLog.d(TAG, "streaming codec started sr=$sampleRate ch=$channels")
        } catch (e: Exception) {
            EdgeLog.e(TAG, "streaming codec start failed", e)
            failed = true
        }
    }

    /** 喂入一个完整 MP3 帧，并立即排空可用的解码输出。 */
    fun push(frame: ByteArray, samplesPerFrame: Int) {
        if (failed) return
        val c = codec ?: return
        try {
            var tries = 0
            var inIdx = c.dequeueInputBuffer(2000)
            while (inIdx < 0 && tries++ < 100) {
                drainAvailable(c)
                inIdx = c.dequeueInputBuffer(2000)
            }
            if (inIdx < 0) return
            val buf = c.getInputBuffer(inIdx) ?: return
            buf.clear()
            buf.put(frame)
            c.queueInputBuffer(inIdx, 0, frame.size, ptsUs, 0)
            ptsUs += samplesPerFrame * 1_000_000L / sampleRate
            drainAvailable(c)
        } catch (e: Exception) {
            EdgeLog.e(TAG, "streaming push failed", e)
            failed = true
            releaseQuietly()
        }
    }

    /** 送入 EOS 并把剩余 PCM 全部排空，释放解码器。 */
    fun finish() {
        if (failed) {
            releaseQuietly()
            return
        }
        val c = codec ?: return
        try {
            var tries = 0
            var inIdx = c.dequeueInputBuffer(2000)
            while (inIdx < 0 && tries++ < 100) {
                drainAvailable(c)
                inIdx = c.dequeueInputBuffer(2000)
            }
            if (inIdx >= 0) c.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            var guard = 0
            while (guard++ < 100000) {
                val outIdx = c.dequeueOutputBuffer(info, 10000)
                when (outIdx) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> {
                        if (outIdx < 0) continue
                        if (info.size > 0) {
                            val out = c.getOutputBuffer(outIdx)
                            if (out != null) {
                                val arr = ByteArray(info.size)
                                out.get(arr)
                                onPcm(arr)
                            }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: Exception) {
            EdgeLog.e(TAG, "streaming finish failed", e)
            failed = true
        } finally {
            releaseQuietly()
        }
    }

    private fun drainAvailable(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            when (outIdx) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> {
                    if (outIdx < 0) return
                    if (info.size > 0) {
                        val out = c.getOutputBuffer(outIdx)
                        if (out != null) {
                            val arr = ByteArray(info.size)
                            out.get(arr)
                            onPcm(arr)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
            }
        }
    }

    private fun releaseQuietly() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (ignored: Exception) {
        }
        try {
            c.release()
        } catch (ignored: Exception) {
        }
    }
}
