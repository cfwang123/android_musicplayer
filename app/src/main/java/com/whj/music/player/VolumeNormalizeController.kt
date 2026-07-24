package com.whj.music.player

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.whj.music.model.PlayableMedia

/**
 * 音量归一化：按曲目平均 RMS 缩放到统一目标。
 * - 过响：MediaPlayer.setVolume 衰减
 * - 过轻：LoudnessEnhancer 提升（mB）
 * 分析结果按 mediaId 缓存，避免重复解码。
 */
class VolumeNormalizeController(
    private val context: Context,
) {
    private var enhancer: LoudnessEnhancer? = null

    /** 当前 MediaPlayer 音量（0~1），供焦点恢复时重设 */
    var playerVolume: Float = 1f
        private set

    /** 最近一次应用的线性增益（1=原音量） */
    var appliedGain: Float = 1f
        private set

    fun attach(audioSessionId: Int) {
        releaseEnhancer()
        if (audioSessionId == 0) return
        try {
            enhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(0)
                enabled = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer attach failed session=$audioSessionId", e)
            enhancer = null
        }
    }

    fun release() {
        releaseEnhancer()
        playerVolume = 1f
        appliedGain = 1f
    }

    private fun releaseEnhancer() {
        try {
            enhancer?.enabled = false
            enhancer?.release()
        } catch (_: Exception) {
            // ignore
        }
        enhancer = null
    }

    fun applyNeutral(player: MediaPlayer?) {
        appliedGain = 1f
        playerVolume = 1f
        setPlayerVolume(player, 1f)
        try {
            enhancer?.setTargetGain(0)
            enhancer?.enabled = false
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * @param linearGain 线性增益，1=不变，&lt;1 衰减，&gt;1 提升
     */
    fun applyGain(player: MediaPlayer?, linearGain: Float) {
        val gain = linearGain.coerceIn(
            VolumeLoudnessAnalyzer.MIN_GAIN,
            VolumeLoudnessAnalyzer.MAX_GAIN,
        )
        appliedGain = gain
        if (gain <= 1f) {
            playerVolume = gain
            setPlayerVolume(player, gain)
            try {
                enhancer?.setTargetGain(0)
                enhancer?.enabled = false
            } catch (_: Exception) {
                // ignore
            }
        } else {
            playerVolume = 1f
            setPlayerVolume(player, 1f)
            val mB = VolumeLoudnessAnalyzer.linearGainToMilliBel(gain)
            try {
                val le = enhancer
                if (le != null) {
                    le.setTargetGain(mB)
                    le.enabled = true
                } else {
                    // 无法提升时保持 1.0，不假装能变大
                    Log.w(TAG, "boost needed but no LoudnessEnhancer, gain=$gain")
                }
            } catch (e: Exception) {
                Log.w(TAG, "setTargetGain failed mB=$mB", e)
            }
        }
    }

    fun reapplyPlayerVolume(player: MediaPlayer?) {
        setPlayerVolume(player, playerVolume)
    }

    private fun setPlayerVolume(player: MediaPlayer?, leftRight: Float) {
        try {
            player?.setVolume(leftRight, leftRight)
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        private const val TAG = "VolumeNormalize"
        private const val PREFS = "volume_norm_cache"
        private const val KEY_PREFIX = "rms_"

        fun getCachedRms(context: Context, mediaId: Long): Float? {
            if (mediaId == 0L) return null
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = KEY_PREFIX + mediaId
            if (!p.contains(key)) return null
            val v = p.getFloat(key, -1f)
            return if (v > 0f) v else null
        }

        fun putCachedRms(context: Context, mediaId: Long, rms: Float) {
            if (mediaId == 0L || rms <= 0f) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_PREFIX + mediaId, rms)
                .apply()
        }

        fun cacheKeyOf(media: PlayableMedia): Long = media.id
    }
}
