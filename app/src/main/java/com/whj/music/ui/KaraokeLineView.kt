package com.whj.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.whj.music.R
import kotlin.math.ceil

/**
 * 多行歌词 + 连续蒙版进度。
 * 白底 UI：当前句淡蓝高亮，其余灰色。
 */
class KaraokeLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val restPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val sungPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private var text: String = ""
    private var progress: Float = 0f
    private var emphasize: Boolean = false
    private var layoutRest: StaticLayout? = null
    private var layoutSung: StaticLayout? = null
    private var lastLayoutWidth = 0
    private var lastEmphasize: Boolean? = null
    private var lastText: String? = null

    fun setLine(text: String, progress: Float, emphasize: Boolean = false) {
        val textChanged = this.text != text
        val styleChanged = this.emphasize != emphasize
        this.text = text
        this.progress = progress.coerceIn(0f, 1f)
        this.emphasize = emphasize
        applyStyle()
        if (textChanged || styleChanged || lastLayoutWidth != usableWidth()) {
            rebuildLayouts()
            requestLayout()
        }
        invalidate()
    }

    private fun applyStyle() {
        val size = sp(14f)
        restPaint.textSize = size
        sungPaint.textSize = size
        restPaint.isFakeBoldText = emphasize
        sungPaint.isFakeBoldText = emphasize
        if (emphasize) {
            restPaint.color = AppTheme.resolveColor(context, R.attr.colorLyricRest, 0xFF9AABBA.toInt())
            sungPaint.color = AppTheme.resolveColor(context, R.attr.colorLyricSung, 0xFF4A90B8.toInt())
        } else {
            restPaint.color = AppTheme.resolveColor(context, R.attr.colorTextMuted, 0xFFA8B5C0.toInt())
            sungPaint.color = restPaint.color
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) rebuildLayouts()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0 && (layoutRest == null || lastLayoutWidth != w - paddingLeft - paddingRight ||
                lastEmphasize != emphasize || lastText != text)
        ) {
            lastLayoutWidth = (w - paddingLeft - paddingRight).coerceAtLeast(1)
            rebuildLayouts(lastLayoutWidth)
        }
        val contentH = layoutRest?.height ?: ceil(restPaint.textSize + sp(4f)).toInt()
        val h = contentH + paddingTop + paddingBottom
        setMeasuredDimension(w, h.coerceAtLeast(ceil(restPaint.textSize + sp(6f)).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rest = layoutRest ?: return
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()

        canvas.save()
        canvas.translate(left, top)
        rest.draw(canvas)
        canvas.restore()

        if (progress <= 0f || !emphasize) return
        val sung = layoutSung ?: return
        val totalWidth = totalLayoutWidth(rest)
        if (totalWidth <= 0f) return
        var remaining = totalWidth * progress

        canvas.save()
        canvas.translate(left, top)
        for (i in 0 until rest.lineCount) {
            if (remaining <= 0f) break
            val lineTop = rest.getLineTop(i)
            val lineBottom = rest.getLineBottom(i)
            val lineLeft = rest.getLineLeft(i)
            val lineWidth = rest.getLineWidth(i)
            if (lineWidth <= 0f) continue
            val drawW = remaining.coerceAtMost(lineWidth)
            canvas.save()
            canvas.clipRect(lineLeft, lineTop.toFloat(), lineLeft + drawW, lineBottom.toFloat())
            sung.draw(canvas)
            canvas.restore()
            remaining -= lineWidth
        }
        canvas.restore()
    }

    private fun usableWidth(): Int {
        val w = width - paddingLeft - paddingRight
        return if (w > 0) w else lastLayoutWidth
    }

    private fun rebuildLayouts(widthOverride: Int = usableWidth()) {
        val w = widthOverride.coerceAtLeast(1)
        lastLayoutWidth = w
        lastEmphasize = emphasize
        lastText = text
        if (text.isEmpty()) {
            layoutRest = null
            layoutSung = null
            return
        }
        layoutRest = buildLayout(text, restPaint, w)
        layoutSung = buildLayout(text, sungPaint, w)
    }

    private fun buildLayout(content: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(sp(2f), 1f)
            .setIncludePad(false)
            .build()
    }

    private fun totalLayoutWidth(layout: StaticLayout): Float {
        var sum = 0f
        for (i in 0 until layout.lineCount) sum += layout.getLineWidth(i)
        return sum
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
