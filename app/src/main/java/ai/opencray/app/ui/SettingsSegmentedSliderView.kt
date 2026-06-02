package ai.opencray.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class SettingsSegmentedSliderView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
  private val rect = RectF()
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1f
    color = Color.argb(51, 242, 201, 76)
  }
  private val innerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1f
    color = Color.argb(204, 255, 255, 255)
  }
  private val innerLowlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1f
    color = Color.argb(8, 0, 0, 0)
  }

  init {
    setLayerType(LAYER_TYPE_SOFTWARE, null)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val radius = height / 2f
    rect.set(0f, 0f, width.toFloat(), height.toFloat())

    drawShadow(canvas, 12f, 0f, 0f, Color.argb(64, 242, 201, 76))
    drawShadow(canvas, 20f, 0f, 0f, Color.argb(38, 242, 201, 76))
    drawShadow(canvas, 6f, 0f, 3f, Color.argb(31, 102, 8, 116))
    drawShadow(canvas, 12f, 0f, 6f, Color.argb(20, 102, 8, 116))
    drawShadow(canvas, 2f, 0f, 1f, Color.argb(38, 102, 8, 116))

    paint.clearShadowLayer()
    paint.style = Paint.Style.FILL
    paint.shader =
      LinearGradient(
        0f,
        0f,
        0f,
        height.toFloat(),
        intArrayOf(
          Color.WHITE,
          Color.rgb(254, 254, 254),
          Color.rgb(249, 249, 249),
        ),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
      )
    canvas.drawRoundRect(rect, radius, radius, paint)
    paint.shader = null

    canvas.drawRoundRect(rect, radius, radius, strokePaint)

    val topInset = 1f
    rect.set(1f, topInset, width - 1f, height - 1f)
    canvas.drawArc(rect, 180f, 180f, false, innerHighlightPaint)
    rect.set(1f, 1f, width - 1f, height - topInset)
    canvas.drawArc(rect, 0f, 180f, false, innerLowlightPaint)
  }

  private fun drawShadow(
    canvas: Canvas,
    radius: Float,
    dx: Float,
    dy: Float,
    color: Int,
  ) {
    paint.shader = null
    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    paint.setShadowLayer(radius, dx, dy, color)
    canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
  }
}
