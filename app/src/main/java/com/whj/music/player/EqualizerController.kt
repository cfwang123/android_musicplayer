package com.whj.music.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import com.whj.music.data.AppSettings

/**
 * 系统 Equalizer 封装。默认关闭；启用后按预设或自定义频段应用。
 */
class EqualizerController(
    private val context: Context,
) {
    private var equalizer: Equalizer? = null

    data class BandInfo(
        val index: Int,
        val centerHz: Int,
        val levelMilliBel: Short,
        val minLevel: Short,
        val maxLevel: Short,
    )

    data class Snapshot(
        val enabled: Boolean,
        val presetIndex: Int,
        val presetNames: List<String>,
        val bands: List<BandInfo>,
        val available: Boolean,
    )

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return
        try {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            applyFromSettings()
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer attach failed session=$audioSessionId", e)
            equalizer = null
        }
    }

    fun release() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Exception) {
            // ignore
        }
        equalizer = null
    }

    fun isAvailable(): Boolean = equalizer != null

    fun snapshot(): Snapshot {
        val eq = equalizer
        if (eq == null) {
            return Snapshot(
                enabled = AppSettings.eqEnabled(context),
                presetIndex = AppSettings.eqPresetIndex(context),
                presetNames = emptyList(),
                bands = emptyList(),
                available = false,
            )
        }
        val names = (0 until eq.numberOfPresets).map { i ->
            runCatching { eq.getPresetName(i.toShort()) }.getOrElse { "Preset $i" }
        }
        val minMax = eq.bandLevelRange
        val min = minMax[0]
        val max = minMax[1]
        val bands = (0 until eq.numberOfBands).map { i ->
            val band = i.toShort()
            BandInfo(
                index = i,
                centerHz = eq.getCenterFreq(band) / 1000, // mHz -> Hz
                levelMilliBel = eq.getBandLevel(band),
                minLevel = min,
                maxLevel = max,
            )
        }
        return Snapshot(
            enabled = eq.enabled,
            presetIndex = AppSettings.eqPresetIndex(context),
            presetNames = names,
            bands = bands,
            available = true,
        )
    }

    fun setEnabled(enabled: Boolean) {
        AppSettings.setEqEnabled(context, enabled)
        val eq = equalizer ?: return
        try {
            eq.enabled = enabled
            if (enabled) applyFromSettings()
        } catch (e: Exception) {
            Log.w(TAG, "setEnabled failed", e)
        }
    }

    fun usePreset(index: Int) {
        val eq = equalizer ?: return
        if (index < 0 || index >= eq.numberOfPresets) return
        try {
            eq.usePreset(index.toShort())
            AppSettings.setEqPresetIndex(context, index)
            // 同步自定义频段存储
            val levels = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
            AppSettings.setEqBandLevels(context, levels)
            if (!eq.enabled && AppSettings.eqEnabled(context)) {
                eq.enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "usePreset failed index=$index", e)
        }
    }

    fun setBandLevel(bandIndex: Int, levelMilliBel: Short) {
        val eq = equalizer ?: return
        if (bandIndex !in 0 until eq.numberOfBands) return
        try {
            eq.setBandLevel(bandIndex.toShort(), levelMilliBel)
            AppSettings.setEqPresetIndex(context, PRESET_CUSTOM)
            val levels = (0 until eq.numberOfBands).map { i ->
                if (i == bandIndex) levelMilliBel.toInt() else eq.getBandLevel(i.toShort()).toInt()
            }
            AppSettings.setEqBandLevels(context, levels)
            if (AppSettings.eqEnabled(context)) {
                eq.enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "setBandLevel failed", e)
        }
    }

    private fun applyFromSettings() {
        val eq = equalizer ?: return
        val enabled = AppSettings.eqEnabled(context)
        try {
            val preset = AppSettings.eqPresetIndex(context)
            if (preset >= 0 && preset < eq.numberOfPresets) {
                eq.usePreset(preset.toShort())
            } else {
                val saved = AppSettings.eqBandLevels(context)
                if (saved.size == eq.numberOfBands.toInt()) {
                    saved.forEachIndexed { i, level ->
                        val range = eq.bandLevelRange
                        val min = range[0].toInt()
                        val max = range[1].toInt()
                        val clamped = level.coerceIn(min, max).toShort()
                        eq.setBandLevel(i.toShort(), clamped)
                    }
                }
            }
            eq.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "applyFromSettings failed", e)
        }
    }

    companion object {
        private const val TAG = "EqualizerController"
        const val PRESET_CUSTOM = -1
    }
}
