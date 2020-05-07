package com.getbouncer.scan.ui.card

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Size
import android.util.TypedValue
import android.view.View
import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
private fun RectF.scaled(scaledSize: Size): RectF {
    return RectF(
        this.left * scaledSize.width,
        this.top * scaledSize.height,
        this.right * scaledSize.width,
        this.bottom * scaledSize.height
    )
}

/**
 * A detection box to display on the debug overlay.
 */
data class DetectionBox(
    val rect: RectF,

    val confidence: Float,

    val label: String
)

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class DebugOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2F
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            20F,
            resources.displayMetrics
        )
        textAlign = Paint.Align.LEFT
    }

    private var boxes: Collection<DetectionBox>? = null

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas != null) drawBoxes(canvas)
    }

    private fun drawBoxes(canvas: Canvas) {
        boxes?.forEach {
            paint.color = getPaintColor(it.confidence)
            textPaint.color = getPaintColor(it.confidence)
            val rect = it.rect.scaled(Size(this.width, this.height))
            canvas.drawRect(rect, paint)
            canvas.drawText(it.label, rect.left, rect.bottom, textPaint)
        }
    }

    @Suppress("Deprecation")
    private fun getPaintColor(confidence: Float) = context.resources.getColor(when {
        confidence > 0.75 -> R.color.bouncerDebugHighConfidence
        confidence > 0.5 -> R.color.bouncerDebugMediumConfidence
        else -> R.color.bouncerDebugLowConfidence
    })

    fun setBoxes(boxes: Collection<DetectionBox>?) {
        this.boxes = boxes
        invalidate()
        requestLayout()
    }
}
