package com.whj.music.util

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * 屏幕能力探测：区分彩色屏与墨水屏 / 灰度屏。
 *
 * Android 无统一「是否彩色」API，综合 OEM 特征与系统 feature。
 */
object DisplayCompat {

    @Volatile
    private var cachedNonColor: Boolean? = null

    /** 非彩色屏（墨水屏等）：需用高对比样式 */
    fun isNonColorScreen(context: Context): Boolean {
        cachedNonColor?.let { return it }
        val value = detectNonColor(context.applicationContext)
        cachedNonColor = value
        return value
    }

    private fun detectNonColor(context: Context): Boolean {
        val pm = context.packageManager
        // OEM 自定义 feature
        val features = listOf(
            "android.hardware.eink",
            "android.hardware.screen.eink",
            "onyx.hardware.eink",
            "onyx.hardware.screen.eink",
            "com.onyx.android.sdk.eac",
        )
        if (features.any { pm.hasSystemFeature(it) }) return true

        val blob = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.PRODUCT,
            Build.DEVICE,
            Build.HARDWARE,
        ).joinToString(" ").lowercase(Locale.US)

        // 常见安卓墨水屏品牌 / 机型关键字
        val keywords = listOf(
            "onyx", "boox", "hisense", "likebook", "tolino",
            "meebook", "supernote", "bigme", "inkpad", "pocketbook",
            "boyue", "ireader", "jdread", "hanvon", "moaan",
            "energy", "inkbook", "sony_dpt", "dpt-rp", "dpt-cp",
            "remarkable", "hyread", "datam", "icartek", "xteink",
            "e-ink", "eink", "e_ink",
        )
        if (keywords.any { blob.contains(it) }) return true

        // 部分机型 product/device 含 noteair / note_ / leaf 等 BOOX 系列
        // 已由 onyx/boox 覆盖；不再用过宽规则以免误伤

        return false
    }

    /** 测试或设置页可清缓存（一般不需要） */
    fun clearCache() {
        cachedNonColor = null
    }
}
