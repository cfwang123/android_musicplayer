package com.whj.music.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 解码音频样本，计算平均 RMS（0~1，相对 full-scale），用于音量归一化。
 * 长曲目在多处采样，避免只扫前奏。
 */
object VolumeLoudnessAnalyzer {
    private const val TAG = "VolumeLoudness"

    /** 默认目标平均 RMS，约 -18 dBFS（可被设置覆盖） */
    const val DEFAULT_TARGET_RMS = 0.12f

    /** 增益下限（压低过响） */
    const val MIN_GAIN = 0.12f

    /** 增益上限（抬升过轻） */
    const val MAX_GAIN = 3.5f

    /** 每个采样段时长 */
    private const val SEGMENT_US = 8_000_000L

    /** 最多采样段数 */
    private const val MAX_SEGMENTS = 4

    private const val TIMEOUT_US = 10_000L
    private const val MAX_LOOP = 10_000
    private const val MIN_SAMPLES = 2_000L

    fun gainForRms(rms: Float, targetRms: Float = DEFAULT_TARGET_RMS): Float {
        if (rms < 1e-5f) return 1f
        val target = targetRms.coerceIn(0.02f, 0.35f)
        return (target / rms).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /** 线性增益 → LoudnessEnhancer 用的毫贝（100 mB = 1 dB） */
    fun linearGainToMilliBel(linearGain: Float): Int {
        if (linearGain <= 1f) return 0
        val db = 20.0 * log10(linearGain.toDouble())
        return (db * 100.0).toInt().coerceIn(0, 2_000)
    }

    /** RMS → dBFS（0 dB = full-scale） */
    fun rmsToDbFs(rms: Float): Float {
        if (rms < 1e-6f) return -96f
        return (20.0 * log10(rms.toDouble())).toFloat()
    }

    /** 线性增益 → dB */
    fun gainToDb(linearGain: Float): Float {
        if (linearGain < 1e-6f) return -96f
        return (20.0 * log10(linearGain.toDouble())).toFloat()
    }

    /**
     * @return 平均 RMS；失败返回 null
     */
    fun analyzeRms(context: Context, uri: Uri): Float? {
        var sumSq = 0.0
        var sampleCount = 0L
        try {
            val durationUs = peekDurationUs(context, uri)
            val starts = segmentStarts(durationUs)
            for (startUs in starts) {
                val pair = decodeSegment(context, uri, startUs, SEGMENT_US)
                if (pair != null) {
                    sumSq += pair.first
                    sampleCount += pair.second
                }
            }
            if (sampleCount < MIN_SAMPLES) {
                Log.w(TAG, "too few samples: $sampleCount")
                return null
            }
            val rms = sqrt(sumSq / sampleCount).toFloat()
            Log.i(TAG, "rms=$rms samples=$sampleCount segments=${starts.size}")
            return rms
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed uri=$uri", e)
            return null
        }
    }

    private fun peekDurationUs(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = findAudioTrack(extractor) ?: return 0L
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /**
     * 从 [startUs] 起解码约 [lengthUs] 时长，返回 (sumSq, sampleCount)。
     * 每段独立创建 decoder，避免 flush/EOS 状态问题。
     */
    private fun decodeSegment(
        context: Context,
        uri: Uri,
        startUs: Long,
        lengthUs: Long,
    ): Pair<Double, Long>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (!mime.startsWith("audio/")) return null

            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            val endUs = startUs + lengthUs

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sumSq = 0.0
            var sampleCount = 0L
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var loops = 0

            while (!outputEos && loops++ < MAX_LOOP) {
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val input = decoder.getInputBuffer(inIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEos = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, size, pts, 0)
                                extractor.advance()
                                if (pts >= endUs) {
                                    // 本段读够
                                    val eosIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                                    if (eosIndex >= 0) {
                                        decoder.queueInputBuffer(
                                            eosIndex,
                                            0,
                                            0,
                                            0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                        )
                                    }
                                    inputEos = true
                                }
                            }
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            val out = decoder.getOutputBuffer(outIndex)
                            if (out != null) {
                                val pair = accumulate(out, info, decoder.outputFormat)
                                sumSq += pair.first
                                sampleCount += pair.second
                            }
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (eos) outputEos = true
                    }
                }
            }
            if (sampleCount <= 0L) return null
            return sumSq to sampleCount
        } catch (e: Exception) {
            Log.w(TAG, "decodeSegment failed startUs=$startUs", e)
            return null
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
                // ignore
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
                // ignore
            }
            try {
                extractor.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun segmentStarts(durationUs: Long): List<Long> {
        if (durationUs <= 0L) return listOf(0L)
        if (durationUs <= SEGMENT_US) return listOf(0L)
        val usable = (durationUs - SEGMENT_US).coerceAtLeast(0L)
        val n = MAX_SEGMENTS.coerceAtMost(
            ((durationUs + SEGMENT_US - 1) / SEGMENT_US).toInt().coerceAtLeast(1),
        )
        if (n <= 1) return listOf(0L)
        return (0 until n).map { i -> usable * i / (n - 1) }
    }

    private fun accumulate(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        format: MediaFormat,
    ): Pair<Double, Long> {
        val encoding = pcmEncoding(format)
        val ordered = buffer.duplicate().order(ByteOrder.nativeOrder())
        ordered.position(info.offset)
        ordered.limit(info.offset + info.size)
        var sumSq = 0.0
        var n = 0L
        val step = 4
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = ordered.asFloatBuffer()
                var i = 0
                val rem = fb.remaining()
                while (i < rem) {
                    val s = fb.get(i).toDouble()
                    sumSq += s * s
                    n++
                    i += step
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                var i = 0
                val rem = ordered.remaining()
                while (i < rem) {
                    val s = (ordered.get().toInt() and 0xFF) / 128.0 - 1.0
                    sumSq += s * s
                    n++
                    i++
                    // skip remaining of step-1
                    val skip = (step - 1).coerceAtMost(rem - i)
                    if (skip > 0) {
                        ordered.position(ordered.position() + skip)
                        i += skip
                    }
                }
            }
            else -> {
                val sb = ordered.asShortBuffer()
                var i = 0
                val rem = sb.remaining()
                while (i < rem) {
                    val s = sb.get(i).toDouble() / 32768.0
                    sumSq += s * s
                    n++
                    i += step
                }
            }
        }
        return sumSq to n
    }

    private fun pcmEncoding(format: MediaFormat): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            format.containsKey(MediaFormat.KEY_PCM_ENCODING)
        ) {
            return format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        }
        return AudioFormat.ENCODING_PCM_16BIT
    }
}
