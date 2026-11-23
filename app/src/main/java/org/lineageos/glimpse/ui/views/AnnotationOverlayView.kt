/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.lineageos.glimpse.models.BrushColorMode
import org.lineageos.glimpse.models.CustomBrush
import org.lineageos.glimpse.models.CustomShape
import org.lineageos.glimpse.models.MarkupFonts
import org.lineageos.glimpse.models.StampPoint
import org.lineageos.glimpse.models.StampStrokeSampler
import org.lineageos.glimpse.models.SvgBrush
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class MarkupTool {
    NONE,
    DOODLE,
    SHAPES,
    TEXT,
    MOSAIC,
    STICKER,
}

enum class ShapeType {
    ARROW,
    DOUBLE_ARROW,
    RECTANGLE,
    ROUNDED_RECTANGLE,
    ELLIPSE,
    LINE,
    TRIANGLE,
    STAR,
    DIAMOND,
    CUSTOM,
}

enum class ShapeStyle {
    OUTLINE,
    SOLID,
    TRANSLUCENT,
}

enum class BrushType {
    NORMAL,
    HIGHLIGHTER,
    NEON,
    DASHED,
    CUSTOM_TEXTURE,
}

enum class TextAlignment { LEFT, CENTER, RIGHT }

enum class MosaicEffect {
    PIXELATE,
    BLUR,
    BLACKOUT,
    STRIPES,
    NOISE,
    CUSTOM_PATTERN,
}

sealed interface AnnotationItem {
    fun draw(canvas: Canvas)

    data class Doodle(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val brushType: BrushType = BrushType.NORMAL,
        val customTexture: Bitmap? = null,
    ) : AnnotationItem {
        private var paint: Paint? = null
        private var glowPaint: Paint? = null

        override fun draw(canvas: Canvas) {
            val p = paint ?: Paint(Paint.ANTI_ALIAS_FLAG).also { paint = it }
            p.reset()
            p.isAntiAlias = true
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidth

            when (brushType) {
                BrushType.NORMAL -> {
                    p.color = color
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    canvas.drawPath(path, p)
                }
                BrushType.HIGHLIGHTER -> {
                    p.color = Color.argb(100 * Color.alpha(color) / 255, Color.red(color), Color.green(color), Color.blue(color))
                    p.strokeCap = Paint.Cap.SQUARE
                    p.strokeJoin = Paint.Join.BEVEL
                    canvas.drawPath(path, p)
                }
                BrushType.NEON -> {
                    val gp = glowPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).also { glowPaint = it }
                    gp.style = Paint.Style.STROKE
                    gp.strokeCap = Paint.Cap.ROUND
                    gp.strokeJoin = Paint.Join.ROUND
                    gp.strokeWidth = strokeWidth * 2.2f
                    gp.color = Color.argb(90 * Color.alpha(color) / 255, Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawPath(path, gp)

                    p.color = Color.argb(Color.alpha(color), 255, 255, 255)
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    canvas.drawPath(path, p)
                }
                BrushType.DASHED -> {
                    p.color = color
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    p.pathEffect = DashPathEffect(floatArrayOf(strokeWidth * 2.5f, strokeWidth * 1.8f), 0f)
                    canvas.drawPath(path, p)
                }
                BrushType.CUSTOM_TEXTURE -> {
                    if (customTexture != null && !customTexture.isRecycled) {
                        p.shader = BitmapShader(customTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                        p.alpha = Color.alpha(color)
                    } else {
                        p.color = color
                    }
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    canvas.drawPath(path, p)
                }
            }
        }
    }

    data class Shape(
        val type: ShapeType,
        var startX: Float,
        var startY: Float,
        var endX: Float,
        var endY: Float,
        var color: Int,
        var strokeWidth: Float,
        var style: ShapeStyle = ShapeStyle.OUTLINE,
        var customShapeId: String? = null,
        var customPathData: String? = null,
    ) : AnnotationItem {
        private var strokePaint: Paint? = null
        private var fillPaint: Paint? = null
        private var transPaint: Paint? = null

        override fun draw(canvas: Canvas) {
            val sPaint = strokePaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }.also { strokePaint = it }

            val fPaint = fillPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }.also { fillPaint = it }

            val tPaint = transPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }.also { transPaint = it }

            sPaint.color = color
            sPaint.strokeWidth = strokeWidth
            fPaint.color = color
            tPaint.color = Color.argb(90 * Color.alpha(color) / 255, Color.red(color), Color.green(color), Color.blue(color))

            val l = min(startX, endX)
            val t = min(startY, endY)
            val r = max(startX, endX)
            val b = max(startY, endY)
            val w = r - l
            val h = b - t
            val cx = (l + r) / 2f
            val cy = (t + b) / 2f

            when (type) {
                ShapeType.CUSTOM -> {
                    val pathData = customPathData
                    if (!pathData.isNullOrBlank() && w > 0f && h > 0f) {
                        val unitPath = AnnotationOverlayView.getOrCreateCachedPath(pathData)
                        val m = Matrix()
                        m.setScale(w, h)
                        m.postTranslate(l, t)
                        val renderPath = Path()
                        unitPath.transform(m, renderPath)
                        when (style) {
                            ShapeStyle.SOLID -> canvas.drawPath(renderPath, fPaint)
                            ShapeStyle.TRANSLUCENT -> {
                                canvas.drawPath(renderPath, tPaint)
                                canvas.drawPath(renderPath, sPaint)
                            }
                            ShapeStyle.OUTLINE -> canvas.drawPath(renderPath, sPaint)
                        }
                    }
                }
                ShapeType.LINE -> {
                    canvas.drawLine(startX, startY, endX, endY, sPaint)
                }
                ShapeType.RECTANGLE -> {
                    when (style) {
                        ShapeStyle.SOLID -> canvas.drawRect(l, t, r, b, fPaint)
                        ShapeStyle.TRANSLUCENT -> {
                            canvas.drawRect(l, t, r, b, tPaint)
                            canvas.drawRect(l, t, r, b, sPaint)
                        }
                        ShapeStyle.OUTLINE -> canvas.drawRect(l, t, r, b, sPaint)
                    }
                }
                ShapeType.ROUNDED_RECTANGLE -> {
                    val cr = min(w, h) * 0.2f
                    when (style) {
                        ShapeStyle.SOLID -> canvas.drawRoundRect(l, t, r, b, cr, cr, fPaint)
                        ShapeStyle.TRANSLUCENT -> {
                            canvas.drawRoundRect(l, t, r, b, cr, cr, tPaint)
                            canvas.drawRoundRect(l, t, r, b, cr, cr, sPaint)
                        }
                        ShapeStyle.OUTLINE -> canvas.drawRoundRect(l, t, r, b, cr, cr, sPaint)
                    }
                }
                ShapeType.ELLIPSE -> {
                    val rect = RectF(l, t, r, b)
                    when (style) {
                        ShapeStyle.SOLID -> canvas.drawOval(rect, fPaint)
                        ShapeStyle.TRANSLUCENT -> {
                            canvas.drawOval(rect, tPaint)
                            canvas.drawOval(rect, sPaint)
                        }
                        ShapeStyle.OUTLINE -> canvas.drawOval(rect, sPaint)
                    }
                }
                ShapeType.TRIANGLE -> {
                    val path = Path().apply {
                        moveTo(cx, t)
                        lineTo(r, b)
                        lineTo(l, b)
                        close()
                    }
                    when (style) {
                        ShapeStyle.SOLID -> canvas.drawPath(path, fPaint)
                        ShapeStyle.TRANSLUCENT -> {
                            canvas.drawPath(path, tPaint)
                            canvas.drawPath(path, sPaint)
                        }
                        ShapeStyle.OUTLINE -> canvas.drawPath(path, sPaint)
                    }
                }
                ShapeType.DIAMOND -> {
                    val path = Path().apply {
                        moveTo(cx, t)
                        lineTo(r, cy)
                        lineTo(cx, b)
                        lineTo(l, cy)
                        close()
                    }
                    when (style) {
                        ShapeStyle.SOLID -> canvas.drawPath(path, fPaint)
                        ShapeStyle.TRANSLUCENT -> {
                            canvas.drawPath(path, tPaint)
                            canvas.drawPath(path, sPaint)
                        }
                        ShapeStyle.OUTLINE -> canvas.drawPath(path, sPaint)
                    }
                }
                ShapeType.STAR -> {
                    val path = createStarPath(cx, cy, min(w, h) / 2f)
                    when (style) {
                        ShapeStyle.SOLID -> canvas.drawPath(path, fPaint)
                        ShapeStyle.TRANSLUCENT -> {
                            canvas.drawPath(path, tPaint)
                            canvas.drawPath(path, sPaint)
                        }
                        ShapeStyle.OUTLINE -> canvas.drawPath(path, sPaint)
                    }
                }
                ShapeType.ARROW -> {
                    canvas.drawLine(startX, startY, endX, endY, sPaint)
                    drawArrowHead(canvas, startX, startY, endX, endY, strokeWidth, fPaint)
                }
                ShapeType.DOUBLE_ARROW -> {
                    canvas.drawLine(startX, startY, endX, endY, sPaint)
                    drawArrowHead(canvas, startX, startY, endX, endY, strokeWidth, fPaint)
                    drawArrowHead(canvas, endX, endY, startX, startY, strokeWidth, fPaint)
                }
            }
        }

        private fun createStarPath(cx: Float, cy: Float, radius: Float): Path {
            val path = Path()
            if (radius <= 0f) return path
            val innerRadius = radius * 0.45f
            val angleStep = Math.PI / 5.0
            var angle = -Math.PI / 2.0

            path.moveTo(
                (cx + radius * cos(angle)).toFloat(),
                (cy + radius * sin(angle)).toFloat()
            )
            for (i in 1..10) {
                val r = if (i % 2 == 1) innerRadius else radius
                angle += angleStep
                path.lineTo(
                    (cx + r * cos(angle)).toFloat(),
                    (cy + r * sin(angle)).toFloat()
                )
            }
            path.close()
            return path
        }

        private fun drawArrowHead(
            canvas: Canvas,
            sx: Float,
            sy: Float,
            ex: Float,
            ey: Float,
            strokeWidth: Float,
            paint: Paint,
        ) {
            val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
            val arrowLen = max(24f, strokeWidth * 3.5f)
            val arrowAngle = Math.PI / 6.0

            val x1 = (ex - arrowLen * cos(angle - arrowAngle)).toFloat()
            val y1 = (ey - arrowLen * sin(angle - arrowAngle)).toFloat()
            val x2 = (ex - arrowLen * cos(angle + arrowAngle)).toFloat()
            val y2 = (ey - arrowLen * sin(angle + arrowAngle)).toFloat()

            val headPath = Path().apply {
                moveTo(ex, ey)
                lineTo(x1, y1)
                lineTo(x2, y2)
                close()
            }
            canvas.drawPath(headPath, paint)
        }
    }

    /** The anchor is the centre of a fixed-width box in base-image coordinates.
     * Width and text size scale together; explicit newlines and soft wraps use the
     * same layout for drawing and selection. Alignment is physical, not relative
     * to the paragraph direction. Android's text shaper still handles bidi runs.
     */
    data class Text(
        var text: String,
        var centerX: Float,
        var centerY: Float,
        var baseTextSize: Float = 32f,
        var scale: Float = 1.0f,
        var color: Int = Color.WHITE,
        var hasBackgroundPill: Boolean = true,
        // null preserves the original automatic contrast background.
        var backgroundColor: Int? = null,
        var fontId: String = "sans",
        var alignment: TextAlignment = TextAlignment.CENTER,
        var boxWidth: Float = baseTextSize * 8f,
        var rotation: Float = 0f,
    ) : AnnotationItem {
        val effectiveTextSize: Float get() = (baseTextSize * scale).coerceAtLeast(1f)

        private fun layout(): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = effectiveTextSize
                color = this@Text.color
                typeface = MarkupFonts.resolve(fontId)
            }
            val align = when (alignment) {
                TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
                TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            }
            return StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length,
                paint, (boxWidth * scale).toInt().coerceAtLeast(1))
                .setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.LTR)
                .setIncludePad(true)
                .build()
        }

        private fun unrotatedBounds(outRect: RectF) {
            val layout = layout()
            val padX = effectiveTextSize * 0.45f
            val padY = effectiveTextSize * 0.3f
            val w = layout.width + padX * 2f
            val h = layout.height + padY * 2f
            outRect.set(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
        }

        fun computeBounds(outRect: RectF) {
            unrotatedBounds(outRect)
            Matrix().apply { setRotate(rotation, centerX, centerY) }.mapRect(outRect)
        }

        fun contains(x: Float, y: Float): Boolean {
            val p = floatArrayOf(x, y)
            Matrix().apply { setRotate(-rotation, centerX, centerY) }.mapPoints(p)
            return RectF().also { unrotatedBounds(it) }.contains(p[0], p[1])
        }

        override fun draw(canvas: Canvas) {
            if (text.isBlank()) return
            val layout = layout()
            val bounds = RectF().also { unrotatedBounds(it) }
            canvas.save()
            canvas.rotate(rotation, centerX, centerY)
            if (hasBackgroundPill) {
                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = backgroundColor ?: if (this@Text.color == Color.WHITE) Color.argb(160, 0, 0, 0) else Color.argb(160, 255, 255, 255)
                    style = Paint.Style.FILL
                }
                val radius = bounds.height() * 0.25f
                canvas.drawRoundRect(bounds, radius, radius, pillPaint)
            }
            canvas.save()
            canvas.translate(centerX - layout.width / 2f, centerY - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
            canvas.restore()
        }
    }

    data class Mosaic(
        val path: Path? = null,
        val brushSize: Float,
        val effect: MosaicEffect = MosaicEffect.PIXELATE,
        val cellSize: Int = 16,
        val color: Int = Color.BLACK,
        val customPattern: Bitmap? = null,
    ) : AnnotationItem {
        override fun draw(canvas: Canvas) {
            // Handled directly via shader in onDraw/rasterize
        }
    }

    data class Sticker(
        val bitmap: Bitmap,
        var centerX: Float,
        var centerY: Float,
        var scale: Float = 1.0f,
        var rotation: Float = 0f,
    ) : AnnotationItem {
        fun computeBounds(outRect: RectF) {
            val radians = Math.toRadians(rotation.toDouble())
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val rotatedW = (abs(cos(radians)) * w + abs(sin(radians)) * h).toFloat()
            val rotatedH = (abs(sin(radians)) * w + abs(cos(radians)) * h).toFloat()
            outRect.set(centerX - rotatedW / 2f, centerY - rotatedH / 2f,
                centerX + rotatedW / 2f, centerY + rotatedH / 2f)
        }

        fun contains(x: Float, y: Float): Boolean {
            val radians = Math.toRadians(-rotation.toDouble())
            val dx = x - centerX
            val dy = y - centerY
            val localX = (dx * cos(radians) - dy * sin(radians)).toFloat()
            val localY = (dx * sin(radians) + dy * cos(radians)).toFloat()
            return abs(localX) <= bitmap.width * scale / 2f && abs(localY) <= bitmap.height * scale / 2f
        }

        override fun draw(canvas: Canvas) {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.rotate(rotation)
            canvas.scale(scale, scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, -w / 2f, -h / 2f, paint)
            canvas.restore()
        }
    }

    data class StampStroke(
        val brushId: String,
        val points: List<StampPoint>,
        val sizeInImage: Float,
        val sizeDp: Float,
        val color: Int,
        val opacity: Int,
        val colorMode: BrushColorMode,
        val tipBitmap: Bitmap?,
        val tipVector: SvgBrush? = null,
    ) : AnnotationItem {
        override fun draw(canvas: Canvas) {
            // Handled via drawWithMatrix
        }

        fun drawWithMatrix(canvas: Canvas, imageMatrix: Matrix?, reuseMatrix: Matrix, reusePaint: Paint) {
            tipVector?.let { vector ->
                canvas.save()
                if (imageMatrix != null) canvas.concat(imageMatrix)
                for (pt in points) {
                    canvas.save()
                    canvas.translate(pt.x, pt.y)
                    canvas.rotate(pt.angle)
                    vector.draw(canvas, sizeInImage, color, opacity, colorMode)
                    canvas.restore()
                }
                canvas.restore()
                return
            }
            val bmp = tipBitmap ?: return
            if (bmp.isRecycled || points.isEmpty()) return

            val bw = bmp.width.toFloat()
            val bh = bmp.height.toFloat()
            val maxDim = max(bw, bh)
            if (maxDim <= 0f) return
            val tipScale = sizeInImage / maxDim
            val halfW = bw / 2f
            val halfH = bh / 2f

            reusePaint.reset()
            reusePaint.isAntiAlias = true
            reusePaint.isFilterBitmap = true
            reusePaint.alpha = (opacity * 255 / 100).coerceIn(0, 255)
            if (colorMode == BrushColorMode.TINT) {
                reusePaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            } else {
                reusePaint.colorFilter = null
            }

            canvas.save()
            if (imageMatrix != null) {
                canvas.concat(imageMatrix)
            }

            for (pt in points) {
                reuseMatrix.reset()
                reuseMatrix.postTranslate(-halfW, -halfH)
                reuseMatrix.postScale(tipScale, tipScale)
                if (pt.angle != 0f) {
                    reuseMatrix.postRotate(pt.angle)
                }
                reuseMatrix.postTranslate(pt.x, pt.y)
                canvas.drawBitmap(bmp, reuseMatrix, reusePaint)
            }

            canvas.restore()
        }
    }
}

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var activeTool: MarkupTool = MarkupTool.NONE
        set(value) {
            if (field == value) return
            // Switching between markup tools must not discard the selected object.
            // Closing markup still dismisses the handles and selection as before.
            if (value == MarkupTool.NONE) {
                commitActiveShape()
                commitActiveText()
                commitActiveSticker()
            }
            field = value
            invalidate()
        }

    var selectedShapeType: ShapeType = ShapeType.ARROW
        set(value) {
            commitActiveShape()
            field = value
        }

    var activeBrushType: BrushType = BrushType.NORMAL
    var activeShapeStyle: ShapeStyle = ShapeStyle.OUTLINE
    var activeMosaicEffect: MosaicEffect = MosaicEffect.PIXELATE
    var activeCustomBrushTexture: Bitmap? = null
    var activeCustomMosaicPattern: Bitmap? = null
    var activeCustomBrush: CustomBrush? = null
    var activeCustomBrushTipBitmap: Bitmap? = null
    var activeCustomShape: CustomShape? = null

    // Stamp stroke execution state
    private val stampDrawMatrix = Matrix()
    private val stampDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var currentStampSampler: StampStrokeSampler? = null
    private var currentStampSizeInImage: Float = 24f

    var activeColor: Int = Color.parseColor("#F44336")
    var activeStrokeWidth: Float = 8f
    var hasTextBackgroundPill: Boolean = true
    var activeTextBackgroundColor: Int? = null
    var activeMosaicCellSize: Int = 16
    var activeMosaicBrushSize: Float = 32f

    companion object {
        private val pathCache = mutableMapOf<String, Path>()

        fun getOrCreateCachedPath(pathData: String): Path {
            return pathCache.getOrPut(pathData) {
                buildPathFromSvg(pathData)
            }
        }

        private fun buildPathFromSvg(svgPath: String): Path {
            val path = Path()
            val tokenRegex = Regex("""([a-zA-Z])|([-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?)""")
            val tokens = tokenRegex.findAll(svgPath).map { it.value }.toList()
            var i = 0
            var cmd = ' '
            fun nextF(): Float? {
                if (i >= tokens.size) return null
                val t = tokens[i]
                if (t.length == 1 && t[0].isLetter()) return null
                i++
                return t.toFloatOrNull()
            }
            while (i < tokens.size) {
                val tok = tokens[i]
                if (tok.length == 1 && tok[0].isLetter()) {
                    cmd = tok[0]
                    i++
                }
                when (cmd) {
                    'M' -> {
                        val x = nextF() ?: break
                        val y = nextF() ?: break
                        path.moveTo(x, y)
                        cmd = 'L'
                    }
                    'L' -> {
                        val x = nextF() ?: break
                        val y = nextF() ?: break
                        path.lineTo(x, y)
                    }
                    'Q' -> {
                        val x1 = nextF() ?: break
                        val y1 = nextF() ?: break
                        val x = nextF() ?: break
                        val y = nextF() ?: break
                        path.quadTo(x1, y1, x, y)
                    }
                    'C' -> {
                        val x1 = nextF() ?: break
                        val y1 = nextF() ?: break
                        val x2 = nextF() ?: break
                        val y2 = nextF() ?: break
                        val x = nextF() ?: break
                        val y = nextF() ?: break
                        path.cubicTo(x1, y1, x2, y2, x, y)
                    }
                    'Z', 'z' -> {
                        path.close()
                        cmd = ' '
                    }
                    else -> i++
                }
            }
            return path
        }
    }

    // All stored annotation geometry is in the EXIF-normalized base bitmap space.
    // imageMatrixProvider maps base -> view; displayToBase maps transformed bitmap -> base.
    private fun getMatrixScale(matrix: Matrix?): Float {
        if (matrix == null) return 1f
        val values = FloatArray(9)
        matrix.getValues(values)
        return hypot(values[Matrix.MSCALE_X].toDouble(), values[Matrix.MSKEW_Y].toDouble())
            .toFloat().coerceAtLeast(0.0001f)
    }

    fun viewLengthToBase(length: Float): Float = length / getMatrixScale(imageMatrixProvider?.invoke())

    var onAnnotationChanged: (() -> Unit)? = null
    var onTextEditRequested: ((AnnotationItem.Text) -> Unit)? = null
    var onTextSelectionChanged: (() -> Unit)? = null
    var onShapeStarted: (() -> Unit)? = null
    var onDoodleStarted: (() -> Unit)? = null
    var onMosaicStarted: (() -> Unit)? = null
    var cropRectProvider: (() -> RectF)? = null
    var imageBoundsProvider: (() -> RectF)? = null
    /** Crop guides are drawn last so the scrim covers photo and annotations equally. */
    var drawPreviewForeground: ((Canvas) -> Unit)? = null
    var displayBitmapProvider: (() -> Bitmap?)? = null
    var imageMatrixProvider: (() -> Matrix)? = null
    var displayToBaseMatrixProvider: (() -> Matrix)? = null
    var baseBoundsProvider: (() -> RectF)? = null
    var onPreviewGesture: ((Float, Float, Float, Float, Float) -> Unit)? = null
    var isPreviewFrameAvailable: (() -> Boolean)? = null
    var isMarkupRestrictedToCrop: Boolean = false

    val annotations = mutableListOf<AnnotationItem>()

    private fun objectBounds(): RectF? {
        val area = baseBoundsProvider?.invoke()?.let { RectF(it) } ?: return null
        val visible = if (isMarkupRestrictedToCrop) cropRectProvider?.invoke() else imageBoundsProvider?.invoke()
        if (visible != null) {
            val inverse = Matrix()
            if (imageMatrixProvider?.invoke()?.invert(inverse) != true) return null
            val mapped = RectF(visible)
            inverse.mapRect(mapped)
            if (!area.intersect(mapped)) return null
        }
        return area.takeUnless { it.isEmpty }
    }

    fun constrainActiveText() {
        val item = activeText ?: return
        val area = objectBounds() ?: return
        val bounds = RectF().also { item.computeBounds(it) }
        val fit = fitAnnotation(item.centerX, item.centerY, bounds.width(), bounds.height(),
            area.left, area.top, area.right, area.bottom)
        item.scale *= fit.scaleFactor
        item.centerX = fit.centerX
        item.centerY = fit.centerY
    }

    private fun constrainActiveSticker() {
        val item = activeSticker ?: return
        val area = objectBounds() ?: return
        val bounds = RectF().also { item.computeBounds(it) }
        val fit = fitAnnotation(item.centerX, item.centerY, bounds.width(), bounds.height(),
            area.left, area.top, area.right, area.bottom)
        item.scale *= fit.scaleFactor
        item.centerX = fit.centerX
        item.centerY = fit.centerY
    }

    // Current doodle in progress
    private var currentDoodleWidth = 8f
    private var currentMosaicWidth = 32f
    private var currentDoodlePath: Path? = null
    private var lastDoodleX = 0f
    private var lastDoodleY = 0f

    // Current mosaic stroke in progress
    private var currentMosaicPath: Path? = null
    private var lastMosaicX = 0f
    private var lastMosaicY = 0f

    // Shaders cache
    private val mosaicShaderCache = mutableMapOf<Int, BitmapShader>()
    private var blurShader: BitmapShader? = null
    private var stripesShader: BitmapShader? = null
    private var noiseShader: BitmapShader? = null

    // Selection references the ordered document; a blank text draft is the only exception.
    var activeShape: AnnotationItem.Shape? = null
        private set
    // A selected committed shape stays in the list, preserving object identity and z-order.
    private var selectedShapeOriginal: AnnotationItem.Shape? = null

    // Active text item
    var activeTextFontId = "sans"
    var activeTextAlignment = TextAlignment.CENTER
    var activeText: AnnotationItem.Text? = null
        private set

    // Active sticker item
    var activeSticker: AnnotationItem.Sticker? = null
        private set

    // Handle dragging state
    private var draggedHandle: Int = -1 // 0: start, 1: end, -1: none
    private var isMovingShape = false
    private var shapeDragStartX = 0f
    private var shapeDragStartY = 0f
    private var isDraggingTextHandle: Boolean = false
    private var isRotatingTextHandle: Boolean = false
    private var isMovingText: Boolean = false
    private var textDragStartX = 0f
    private var textDragStartY = 0f
    private var textInitialDist = 0f
    private var textInitialScale = 1f
    private var textInitialAngle = 0f
    private var textBaseRotation = 0f
    private var lastTapTime = 0L

    // Sticker dragging state
    private var isDraggingStickerHandle: Boolean = false
    private var isMovingSticker: Boolean = false
    private var stickerDragStartX = 0f
    private var stickerDragStartY = 0f
    private var stickerInitialDist = 0f
    private var stickerInitialScale = 1f
    private var stickerInitialAngle = 0f
    private var stickerBaseRotation = 0f

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 3f
    }
    private val boundsDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    fun invalidateMosaicCache() {
        mosaicShaderCache.clear()
        blurShader = null
        stripesShader = null
        noiseShader = null
        invalidate()
    }

    private fun getOrCreateMosaicShader(cellSize: Int): BitmapShader? {
        val cached = mosaicShaderCache[cellSize]
        if (cached != null) {
            displayToBaseMatrixProvider?.invoke()?.let { cached.setLocalMatrix(it) }
            return cached
        }

        val bmp = displayBitmapProvider?.invoke() ?: return null
        val cs = cellSize.coerceAtLeast(2)
        val sW = max(1, bmp.width / cs)
        val sH = max(1, bmp.height / cs)
        val sBmp = Bitmap.createScaledBitmap(bmp, sW, sH, false)
        val mBmp = Bitmap.createScaledBitmap(sBmp, bmp.width, bmp.height, false)
        if (sBmp !== mBmp && sBmp !== bmp) sBmp.recycle()

        val shader = BitmapShader(mBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        displayToBaseMatrixProvider?.invoke()?.let { shader.setLocalMatrix(it) }
        mosaicShaderCache[cellSize] = shader
        return shader
    }

    private fun getOrCreateBlurShader(sourceBitmap: Bitmap? = null): BitmapShader? {
        val bmp = sourceBitmap ?: displayBitmapProvider?.invoke() ?: return null
        if (sourceBitmap == null && blurShader != null) {
            displayToBaseMatrixProvider?.invoke()?.let { blurShader?.setLocalMatrix(it) }
            return blurShader
        }

        val downW = max(4, bmp.width / 16)
        val downH = max(4, bmp.height / 16)
        val small = Bitmap.createScaledBitmap(bmp, downW, downH, true)
        val blurred = Bitmap.createScaledBitmap(small, bmp.width, bmp.height, true)
        if (small !== bmp && small !== blurred) small.recycle()
        val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (sourceBitmap == null) {
            displayToBaseMatrixProvider?.invoke()?.let { shader.setLocalMatrix(it) }
            blurShader = shader
        }
        return shader
    }

    private fun getOrCreateStripesShader(): BitmapShader {
        stripesShader?.let { return it }
        val size = 32
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#1C1B1F"))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5A623")
            strokeWidth = 6f
            style = Paint.Style.STROKE
        }
        for (i in -size until size * 2 step 12) {
            c.drawLine(i.toFloat(), 0f, (i + size).toFloat(), size.toFloat(), p)
        }
        val s = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        stripesShader = s
        return s
    }

    private fun getOrCreateNoiseShader(): BitmapShader {
        noiseShader?.let { return it }
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(42)
        val pixels = IntArray(size * size) {
            val v = 30 + random.nextInt(195)
            Color.rgb(v, v, v)
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        val s = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        noiseShader = s
        return s
    }

    fun commitActiveShape() {
        if (activeShape == null) return
        activeShape = null
        selectedShapeOriginal = null
        draggedHandle = -1
        isMovingShape = false
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun commitActiveText() {
        if (activeText == null) return
        activeText = null
        isDraggingTextHandle = false
        isRotatingTextHandle = false
        isMovingText = false
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun commitActiveSticker() {
        if (activeSticker == null) return
        activeSticker = null
        isDraggingStickerHandle = false
        isMovingSticker = false
        onAnnotationChanged?.invoke()
        invalidate()
    }

    /** The caller must not mutate or recycle bmp while the document or history can reference it. */
    fun addSticker(bmp: Bitmap) {
        val before = historyState()
        commitActiveShape()
        commitActiveText()
        commitActiveSticker()

        activeTool = MarkupTool.STICKER
        val bounds = cropRectProvider?.invoke()?.takeUnless { it.isEmpty }
            ?: imageBoundsProvider?.invoke()
        val point = floatArrayOf(bounds?.centerX() ?: (width / 2f), bounds?.centerY() ?: (height / 2f))
        val inverse = Matrix()
        imageMatrixProvider?.invoke()?.invert(inverse)
        inverse.mapPoints(point)
        val cx = point[0]
        val cy = point[1]
        val density = resources.displayMetrics.density
        val targetSize = minOf(130f * density, (bounds?.width() ?: width.toFloat()) * 0.7f,
            (bounds?.height() ?: height.toFloat()) * 0.7f)
        val maxDim = max(bmp.width, bmp.height).toFloat()
        val initialScale = if (maxDim > 0f) (viewLengthToBase(targetSize) / maxDim).coerceAtMost(3.0f) else 1f

        activeSticker = AnnotationItem.Sticker(
            bitmap = bmp,
            centerX = cx,
            centerY = cy,
            scale = initialScale,
        )
        annotations.add(requireNotNull(activeSticker))
        constrainActiveSticker()
        recordHistory(before)
        invalidate()
    }

    fun addTextItem(text: String, cx: Float, cy: Float) {
        val before = historyState()
        commitActiveShape()
        commitActiveText()
        commitActiveSticker()
        activeTool = MarkupTool.TEXT
        val point = floatArrayOf(cx, cy)
        val inverse = Matrix()
        imageMatrixProvider?.invoke()?.invert(inverse)
        inverse.mapPoints(point)
        val item = AnnotationItem.Text(
            text = text,
            centerX = point[0],
            centerY = point[1],
            baseTextSize = viewLengthToBase(32f),
            color = activeColor or 0xFF000000.toInt(),
            hasBackgroundPill = hasTextBackgroundPill,
            backgroundColor = activeTextBackgroundColor,
            fontId = activeTextFontId, alignment = activeTextAlignment,
        )
        activeText = item
        if (text.isNotBlank()) annotations.add(item)
        recordHistory(before)
        invalidate()
    }

    /** Values are independent snapshots; originals preserve identity for editable selections. */
    private data class HistoryEntry(val original: AnnotationItem, val value: AnnotationItem)
    private data class HistoryState(
        val items: List<HistoryEntry>,
        val shapeIndex: Int, val textIndex: Int, val stickerIndex: Int,
        val selectedOriginal: AnnotationItem.Shape?, val textDraft: HistoryEntry?,
    )
    private data class HistoryAction(val before: HistoryState, val after: HistoryState)
    private val undoHistory = mutableListOf<HistoryAction>()
    private val redoHistory = mutableListOf<HistoryAction>()
    private data class RetainedDocument(
        val state: HistoryState,
        val undo: List<HistoryAction>,
        val redo: List<HistoryAction>,
        val fontId: String,
        val alignment: TextAlignment,
    )

    // Non-configuration state contains annotation values only, never the old View or Activity.
    fun retainDocument(): Any = RetainedDocument(historyState(), undoHistory.toList(), redoHistory.toList(),
        activeTextFontId, activeTextAlignment)

    fun restoreRetainedDocument(document: Any?) {
        val saved = document as? RetainedDocument ?: return
        undoHistory.clear(); undoHistory.addAll(saved.undo)
        redoHistory.clear(); redoHistory.addAll(saved.redo)
        activeTextFontId = saved.fontId
        activeTextAlignment = saved.alignment
        restoreHistory(saved.state)
    }

    private var gestureHistoryStart: HistoryState? = null
    private var controlHistoryStart: HistoryState? = null

    // Bitmaps and SvgBrush vectors are shared read-only: callers must retain them and
    // must not mutate/recycle them while a document or history entry can reference them.
    // Evicting a history entry drops references only; it never recycles a bitmap.
    private fun historyEntry(item: AnnotationItem) = HistoryEntry(item, copyItem(item))

    private fun historyState(): HistoryState = HistoryState(
        annotations.map(::historyEntry),
        annotations.indexOfFirst { it === activeShape },
        annotations.indexOfFirst { it === activeText },
        annotations.indexOfFirst { it === activeSticker },
        selectedShapeOriginal?.copy(),
        activeText?.takeIf { it.text.isBlank() && annotations.none { item -> item === it } }
            ?.let(::historyEntry),
    )

    // Path has no structural equals on supported Android versions. Include fill type
    // and a fine geometric approximation rather than comparing Path object identities.
    private fun samePath(a: Path?, b: Path?): Boolean = when {
        a == null || b == null -> a == null && b == null
        a.fillType != b.fillType -> false
        else -> a.approximate(0.01f).contentEquals(b.approximate(0.01f))
    }

    private fun sameItem(a: AnnotationItem, b: AnnotationItem): Boolean = when {
        a is AnnotationItem.Doodle && b is AnnotationItem.Doodle ->
            a.color == b.color && a.strokeWidth == b.strokeWidth && a.brushType == b.brushType &&
                a.customTexture === b.customTexture && samePath(a.path, b.path)
        a is AnnotationItem.Mosaic && b is AnnotationItem.Mosaic ->
            a.brushSize == b.brushSize && a.effect == b.effect && a.cellSize == b.cellSize &&
                a.color == b.color && a.customPattern === b.customPattern && samePath(a.path, b.path)
        else -> a == b
    }

    // Compare document order one-to-one. Selection and an empty draft are not content.
    private fun sameDocument(a: HistoryState, b: HistoryState): Boolean =
        a.items.size == b.items.size && a.items.indices.all { i -> sameItem(a.items[i].value, b.items[i].value) }

    private fun restoreEntry(entry: HistoryEntry): AnnotationItem {
        val target = entry.original
        when (val saved = entry.value) {
            is AnnotationItem.Shape -> restoreShape(target as AnnotationItem.Shape, saved)
            is AnnotationItem.Text -> (target as AnnotationItem.Text).apply {
                text = saved.text; centerX = saved.centerX; centerY = saved.centerY
                baseTextSize = saved.baseTextSize; scale = saved.scale
                color = saved.color; hasBackgroundPill = saved.hasBackgroundPill; backgroundColor = saved.backgroundColor
                fontId = saved.fontId; alignment = saved.alignment; boxWidth = saved.boxWidth
                rotation = saved.rotation
            }
            is AnnotationItem.Sticker -> (target as AnnotationItem.Sticker).apply {
                centerX = saved.centerX; centerY = saved.centerY
                scale = saved.scale; rotation = saved.rotation
            }
            is AnnotationItem.Doodle -> (target as AnnotationItem.Doodle).path.set(saved.path)
            is AnnotationItem.Mosaic -> {
                val path = (target as AnnotationItem.Mosaic).path
                if (path != null && saved.path != null) path.set(saved.path)
            }
            // A stamp's list can be backed by a mutable list; restore a fresh copy.
            is AnnotationItem.StampStroke -> return copyItem(saved)
        }
        return target
    }

    private fun restoreHistory(state: HistoryState, restoreDraft: Boolean = false) {
        annotations.clear()
        annotations.addAll(state.items.map(::restoreEntry))
        activeShape = if (activeTool == MarkupTool.SHAPES)
            annotations.getOrNull(state.shapeIndex) as? AnnotationItem.Shape else null
        activeText = if (activeTool == MarkupTool.TEXT) {
            (annotations.getOrNull(state.textIndex) as? AnnotationItem.Text)
                ?: if (restoreDraft) state.textDraft?.let { restoreEntry(it) as AnnotationItem.Text } else null
        } else null
        activeSticker = if (activeTool == MarkupTool.STICKER)
            annotations.getOrNull(state.stickerIndex) as? AnnotationItem.Sticker else null
        selectedShapeOriginal = if (activeShape != null) state.selectedOriginal?.copy() else null
        activeShape?.let {
            activeColor = it.color
            activeShapeStyle = it.style
            activeStrokeWidth = it.strokeWidth * getMatrixScale(imageMatrixProvider?.invoke())
        }
        activeText?.let { activeColor = it.color; hasTextBackgroundPill = it.hasBackgroundPill; activeTextBackgroundColor = it.backgroundColor; activeTextFontId = it.fontId; activeTextAlignment = it.alignment }
        draggedHandle = -1; isMovingShape = false
        isMovingText = false; isDraggingTextHandle = false; isRotatingTextHandle = false
        isMovingSticker = false; isDraggingStickerHandle = false
        invalidate()
        onAnnotationChanged?.invoke()
    }

    private fun recordHistory(before: HistoryState) {
        val after = historyState()
        if (!sameDocument(before, after)) {
            undoHistory.add(HistoryAction(before, after))
            if (undoHistory.size > 50) undoHistory.removeAt(0)
            redoHistory.clear()
        }
        onAnnotationChanged?.invoke()
    }

    fun canUndo(): Boolean = undoHistory.isNotEmpty()
    fun canRedo(): Boolean = redoHistory.isNotEmpty()

    fun undo(): Boolean {
        val action = undoHistory.removeLastOrNull() ?: return false
        redoHistory.add(action)
        restoreHistory(action.before)
        return true
    }

    fun redo(): Boolean {
        val action = redoHistory.removeLastOrNull() ?: return false
        undoHistory.add(action)
        restoreHistory(action.after)
        return true
    }

    /** Group repeated slider changes into one history action. */
    fun beginControlEdit() { if (controlHistoryStart == null) controlHistoryStart = historyState() }
    fun endControlEdit() { controlHistoryStart?.let(::recordHistory); controlHistoryStart = null }
    fun updateControlEdit(edit: () -> Unit) {
        if (controlHistoryStart != null) { edit(); invalidate() } else editAnnotation(edit)
    }
    fun editAnnotation(edit: () -> Unit) {
        val before = historyState()
        edit()
        invalidate()
        recordHistory(before)
    }

    fun editText(item: AnnotationItem.Text, text: String) {
        editAnnotation {
            if (text.isBlank()) {
                if (activeText === item) activeText = null
                annotations.removeAll { it === item }
            } else {
                item.text = text
                if (annotations.none { it === item }) annotations.add(item)
                constrainActiveText()
            }
        }
    }

    fun discardEmptyText(item: AnnotationItem.Text) {
        if (activeText === item && item.text.isBlank()) {
            activeText = null
            invalidate()
            onAnnotationChanged?.invoke()
        }
    }

    fun clear() {
        val before = historyState()
        activeShape = null
        selectedShapeOriginal = null
        activeText = null
        activeSticker = null
        draggedHandle = -1
        annotations.clear()
        invalidate()
        recordHistory(before)
    }

    fun hasAnnotations(): Boolean = annotations.isNotEmpty()

    /** Draw one item in canonical base coordinates; the caller supplies the canvas transform. */
    private fun drawAnnotation(canvas: Canvas, item: AnnotationItem) {
        when (item) {
            is AnnotationItem.StampStroke -> item.drawWithMatrix(canvas, null, stampDrawMatrix, stampDrawPaint)
            is AnnotationItem.Mosaic -> {
                val path = item.path ?: return
                val shader: Shader? = when (item.effect) {
                    MosaicEffect.PIXELATE -> getOrCreateMosaicShader(item.cellSize)
                    MosaicEffect.BLUR -> getOrCreateBlurShader()
                    MosaicEffect.STRIPES -> getOrCreateStripesShader()
                    MosaicEffect.NOISE -> getOrCreateNoiseShader()
                    MosaicEffect.CUSTOM_PATTERN -> item.customPattern?.takeUnless { it.isRecycled }
                        ?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
                    MosaicEffect.BLACKOUT -> null
                }
                if (shader == null && item.effect != MosaicEffect.BLACKOUT) return
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = shader
                    color = item.color
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = item.brushSize
                    isFilterBitmap = item.effect == MosaicEffect.BLUR
                }
                canvas.drawPath(path, paint)
            }
            else -> item.draw(canvas)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isPreviewFrameAvailable?.invoke() == false) return
        val matrix = imageMatrixProvider?.invoke() ?: return
        val bounds = if (isMarkupRestrictedToCrop) cropRectProvider?.invoke()
            else imageBoundsProvider?.invoke() ?: cropRectProvider?.invoke()
        canvas.save()
        if (bounds != null && !bounds.isEmpty) canvas.clipRect(bounds)
        canvas.concat(matrix)
        baseBoundsProvider?.invoke()?.takeUnless { it.isEmpty }?.let { canvas.clipRect(it) }
        annotations.forEach { drawAnnotation(canvas, it) }
        val sampler = currentStampSampler
        val brush = activeCustomBrush
        val tip = activeCustomBrushTipBitmap
        val vector = brush?.tipVector
        if (sampler != null && brush != null && (vector != null || (tip != null && !tip.isRecycled))) {
            drawAnnotation(canvas, AnnotationItem.StampStroke(brush.id, sampler.stamps,
                currentStampSizeInImage, brush.sizeDp, activeColor, brush.opacity, brush.colorMode, tip, vector))
        } else currentDoodlePath?.let {
            drawAnnotation(canvas, AnnotationItem.Doodle(it, activeColor, currentDoodleWidth, activeBrushType, activeCustomBrushTexture))
        }
        currentMosaicPath?.let {
            drawAnnotation(canvas, AnnotationItem.Mosaic(it, currentMosaicWidth, activeMosaicEffect,
                activeMosaicCellSize, Color.BLACK, activeCustomMosaicPattern))
        }
        activeText?.takeIf { draft -> annotations.none { it === draft } }?.draw(canvas)
        canvas.restore()
        drawPreviewForeground?.invoke(canvas)

        // Handle coordinates and hit testing share the same matrix, while handle sizes stay in dp.
        canvas.save()
        canvas.concat(matrix)
        boundsDashPaint.strokeWidth = viewLengthToBase(2f)
        if (activeTool == MarkupTool.SHAPES) activeShape?.let {
            drawHandle(canvas, it.startX, it.startY); drawHandle(canvas, it.endX, it.endY)
        }
        if (activeTool == MarkupTool.TEXT) activeText?.let {
            val rect = RectF().also { bounds -> it.computeBounds(bounds) }
            canvas.drawRoundRect(rect, viewLengthToBase(8f), viewLengthToBase(8f), boundsDashPaint)
            drawHandle(canvas, rect.right, rect.bottom)
            val rotateY = rect.top - viewLengthToBase(36f * resources.displayMetrics.density)
            canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rotateY, boundsDashPaint)
            drawHandle(canvas, rect.centerX(), rotateY)
        }
        if (activeTool == MarkupTool.STICKER) activeSticker?.let {
            val rect = RectF().also { bounds -> it.computeBounds(bounds) }
            canvas.drawRoundRect(rect, viewLengthToBase(8f), viewLengthToBase(8f), boundsDashPaint)
            drawHandle(canvas, rect.right, rect.bottom)
            drawDeleteBadge(canvas, rect.left, rect.top)
        }
        canvas.restore()
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val radius = viewLengthToBase(12f * resources.displayMetrics.density)
        canvas.drawCircle(x, y, radius, handlePaint)
        handleBorderPaint.strokeWidth = viewLengthToBase(3f)
        canvas.drawCircle(x, y, radius, handleBorderPaint)
    }

    private fun drawDeleteBadge(canvas: Canvas, x: Float, y: Float) {
        val radius = viewLengthToBase(12f * resources.displayMetrics.density)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = viewLengthToBase(3f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(x, y, radius, bgPaint)
        val d = viewLengthToBase(6f)
        canvas.drawLine(x - d, y - d, x + d, y + d, xPaint)
        canvas.drawLine(x - d, y + d, x + d, y - d, xPaint)
    }

    private fun findTouchedHandle(shape: AnnotationItem.Shape, viewX: Float, viewY: Float): Int {
        val points = floatArrayOf(shape.startX, shape.startY, shape.endX, shape.endY)
        imageMatrixProvider?.invoke()?.mapPoints(points) ?: return -1
        val first = hypot((viewX - points[0]).toDouble(), (viewY - points[1]).toDouble())
        val second = hypot((viewX - points[2]).toDouble(), (viewY - points[3]).toDouble())
        val radius = 12f * resources.displayMetrics.density
        val startHit = first <= radius ||
            (shape.type == ShapeType.DOUBLE_ARROW && arrowHeadContains(shape, viewX, viewY, false))
        val endHit = second <= radius ||
            ((shape.type == ShapeType.ARROW || shape.type == ShapeType.DOUBLE_ARROW) &&
                arrowHeadContains(shape, viewX, viewY, true))
        return when {
            startHit && endHit -> if (first <= second) 0 else 1
            startHit -> 0
            endHit -> 1
            else -> -1
        }
    }

    /** Match the triangular filled head drawn by Shape, including at high zoom. */
    private fun arrowHeadContains(shape: AnnotationItem.Shape, x: Float, y: Float, end: Boolean): Boolean {
        val sx = if (end) shape.startX else shape.endX
        val sy = if (end) shape.startY else shape.endY
        val ex = if (end) shape.endX else shape.startX
        val ey = if (end) shape.endY else shape.startY
        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val length = max(24f, shape.strokeWidth * 3.5f)
        val points = floatArrayOf(ex, ey,
            (ex - length * cos(angle - Math.PI / 6)).toFloat(),
            (ey - length * sin(angle - Math.PI / 6)).toFloat(),
            (ex - length * cos(angle + Math.PI / 6)).toFloat(),
            (ey - length * sin(angle + Math.PI / 6)).toFloat())
        imageMatrixProvider?.invoke()?.mapPoints(points) ?: return false
        fun cross(i: Int, j: Int): Float =
            (points[j] - points[i]) * (y - points[i + 1]) -
                (points[j + 1] - points[i + 1]) * (x - points[i])
        val a = cross(0, 2); val b = cross(2, 4); val c = cross(4, 0)
        return (a >= 0f && b >= 0f && c >= 0f) || (a <= 0f && b <= 0f && c <= 0f)
    }

    // Render the actual shape (including arrowheads and custom paths) into a small
    // screen-space probe. This avoids bounding-box hits on empty outline interiors.
    private fun hitShapeBody(shape: AnnotationItem.Shape, viewX: Float, viewY: Float): Boolean {
        val matrix = imageMatrixProvider?.invoke() ?: return false
        val radius = max(1, (8f * resources.displayMetrics.density).toInt())
        val side = radius * 2 + 1
        val probe = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(probe)
            canvas.translate(radius - viewX, radius - viewY)
            canvas.concat(matrix)
            shape.draw(canvas)
            for (yy in 0 until side) for (xx in 0 until side) {
                val dx = xx - radius; val dy = yy - radius
                if (dx * dx + dy * dy <= radius * radius && Color.alpha(probe.getPixel(xx, yy)) != 0) return true
            }
            return false
        } finally {
            probe.recycle()
        }
    }

    private fun restoreShape(target: AnnotationItem.Shape, saved: AnnotationItem.Shape) {
        target.startX = saved.startX; target.startY = saved.startY
        target.endX = saved.endX; target.endY = saved.endY
        target.color = saved.color; target.strokeWidth = saved.strokeWidth; target.style = saved.style
        target.customShapeId = saved.customShapeId; target.customPathData = saved.customPathData
    }

    private data class GestureSnapshot(val document: HistoryState, val lastTap: Long)
    private var gestureSnapshot: GestureSnapshot? = null
    private var pendingTextEdit: AnnotationItem.Text? = null
    private var pendingStickerDelete = false
    private var inPreviewGesture = false
    private var suppressUntilUp = false
    private var gestureX = 0f
    private var gestureY = 0f
    private var gestureDistance = 1f

    private fun copyItem(item: AnnotationItem): AnnotationItem = when (item) {
        is AnnotationItem.Shape -> item.copy()
        is AnnotationItem.Text -> item.copy()
        // Bitmaps are treated as read-only by annotation gestures and drawing. Paths and
        // lists are mutable even when held by val and must not alias the transaction.
        is AnnotationItem.Sticker -> item.copy()
        is AnnotationItem.Doodle -> item.copy(path = Path(item.path))
        is AnnotationItem.Mosaic -> item.copy(path = item.path?.let { Path(it) })
        is AnnotationItem.StampStroke -> item.copy(points = item.points.map { it.copy() })
    }

    private fun cancelSingleGesture() {
        gestureSnapshot?.let { snapshot ->
            restoreHistory(snapshot.document, restoreDraft = true)
            lastTapTime = snapshot.lastTap
        }
        currentDoodlePath = null
        currentMosaicPath = null
        currentStampSampler = null
        pendingTextEdit = null
        pendingStickerDelete = false
        draggedHandle = -1
        isMovingShape = false
        isDraggingTextHandle = false
        isRotatingTextHandle = false
        isMovingText = false
        isDraggingStickerHandle = false
        isMovingSticker = false
        onAnnotationChanged?.invoke()
        invalidate()
    }

    private fun setGestureAnchor(event: MotionEvent, first: Int = 0, second: Int = 1) {
        gestureX = (event.getX(first) + event.getX(second)) / 2f
        gestureY = (event.getY(first) + event.getY(second)) / 2f
        gestureDistance = hypot((event.getX(first) - event.getX(second)).toDouble(),
            (event.getY(first) - event.getY(second)).toDouble()).toFloat().coerceAtLeast(1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureHistoryStart = if (activeTool != MarkupTool.NONE &&
                isPreviewFrameAvailable?.invoke() != false) historyState() else null
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> gestureHistoryStart = null
        }
        val handled = handleAnnotationTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            gestureHistoryStart?.let(::recordHistory)
            gestureHistoryStart = null
        }
        return handled
    }

    private fun handleAnnotationTouch(event: MotionEvent): Boolean {
        if (activeTool == MarkupTool.NONE) return false
        if (isPreviewFrameAvailable?.invoke() == false) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) return false
            // A viewport gesture may move the crop off the image; keep consuming its
            // remaining pointers. An interrupted single-pointer edit is rolled back.
            if (!inPreviewGesture && !suppressUntilUp) {
                cancelSingleGesture()
                gestureHistoryStart = null
                suppressUntilUp = true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inPreviewGesture = false
                suppressUntilUp = false
                pendingTextEdit = null
                pendingStickerDelete = false
                gestureSnapshot = GestureSnapshot(historyState(), lastTapTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (suppressUntilUp && !inPreviewGesture) return true
                if (!inPreviewGesture) cancelSingleGesture()
                inPreviewGesture = true
                suppressUntilUp = true
                if (event.pointerCount >= 2) setGestureAnchor(event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // If two fingers remain, re-anchor without a jump when pointer indices
                // are compacted in the following MOVE. Otherwise wait for a fresh DOWN.
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                inPreviewGesture = inPreviewGesture && remaining.size >= 2
                if (inPreviewGesture) setGestureAnchor(event, remaining[0], remaining[1])
                suppressUntilUp = true
                return true
            }
            MotionEvent.ACTION_MOVE -> if (inPreviewGesture && event.pointerCount >= 2) {
                val x = (event.getX(0) + event.getX(1)) / 2f
                val y = (event.getY(0) + event.getY(1)) / 2f
                val distance = hypot((event.getX(0) - event.getX(1)).toDouble(),
                    (event.getY(0) - event.getY(1)).toDouble()).toFloat().coerceAtLeast(1f)
                onPreviewGesture?.invoke(gestureX, gestureY, x, y, distance / gestureDistance)
                gestureX = x; gestureY = y; gestureDistance = distance
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!suppressUntilUp) cancelSingleGesture()
                gestureSnapshot = null
                inPreviewGesture = false
                suppressUntilUp = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                gestureSnapshot = null
                if (suppressUntilUp) {
                    inPreviewGesture = false
                    suppressUntilUp = false
                    return true
                }
            }
        }
        if (suppressUntilUp) return true
        val inverse = Matrix()
        if (imageMatrixProvider?.invoke()?.invert(inverse) != true) return false
        val point = floatArrayOf(event.x, event.y)
        inverse.mapPoints(point)
        val x = point[0]
        val y = point[1]

        when (activeTool) {
            MarkupTool.DOODLE -> {
                val customBrush = activeCustomBrush
                if (customBrush != null) {
                    val imgX = x
                    val imgY = y

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            val scale = getMatrixScale(imageMatrixProvider?.invoke())
                            val sizeInImage = if (scale > 0f) activeStrokeWidth / scale else activeStrokeWidth
                            currentStampSizeInImage = sizeInImage
                            val step = max(1f, (customBrush.spacingPercent / 100f) * sizeInImage)
                            currentStampSampler = StampStrokeSampler(step, customBrush.followDirection, 2000).apply {
                                start(imgX, imgY)
                            }
                            onDoodleStarted?.invoke()
                            invalidate()
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            currentStampSampler?.moveTo(imgX, imgY)
                            invalidate()
                            return true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            currentStampSampler = null
                            invalidate()
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            val sampler = currentStampSampler
                            val bmp = activeCustomBrushTipBitmap
                            val vector = customBrush.tipVector
                            if (sampler != null && sampler.stamps.isNotEmpty() &&
                                (vector != null || (bmp != null && !bmp.isRecycled))) {
                                annotations.add(
                                    AnnotationItem.StampStroke(
                                        brushId = customBrush.id,
                                        points = sampler.stamps.toList(),
                                        sizeInImage = currentStampSizeInImage,
                                        sizeDp = customBrush.sizeDp,
                                        color = activeColor,
                                        opacity = customBrush.opacity,
                                        colorMode = customBrush.colorMode,
                                        tipBitmap = bmp,
                                        tipVector = vector,
                                    )
                                )
                                currentStampSampler = null
                                onAnnotationChanged?.invoke()
                                invalidate()
                            }
                            return true
                        }
                        else -> return false
                    }
                } else {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            currentDoodleWidth = viewLengthToBase(activeStrokeWidth)
                            val path = Path().apply { moveTo(x, y) }
                            currentDoodlePath = path
                            lastDoodleX = x
                            lastDoodleY = y
                            onDoodleStarted?.invoke()
                            invalidate()
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            currentDoodlePath?.let { path ->
                                val dx = kotlin.math.abs(x - lastDoodleX)
                                val dy = kotlin.math.abs(y - lastDoodleY)
                                if (dx >= 2 || dy >= 2) {
                                    path.quadTo(lastDoodleX, lastDoodleY, (x + lastDoodleX) / 2f, (y + lastDoodleY) / 2f)
                                    lastDoodleX = x
                                    lastDoodleY = y
                                    invalidate()
                                }
                            }
                            return true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            currentDoodlePath = null
                            invalidate()
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            currentDoodlePath?.let { path ->
                                path.lineTo(x, y)
                                annotations.add(
                                    AnnotationItem.Doodle(
                                        path = path,
                                        color = activeColor,
                                        strokeWidth = currentDoodleWidth,
                                        brushType = activeBrushType,
                                        customTexture = null,
                                    )
                                )
                                currentDoodlePath = null
                                onAnnotationChanged?.invoke()
                                invalidate()
                            }
                            return true
                        }
                        else -> return false
                    }
                }
            }

            MarkupTool.SHAPES -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val existing = activeShape
                        val candidates = annotations.asReversed().filterIsInstance<AnnotationItem.Shape>()
                            .toMutableList()
                        if (existing != null && annotations.none { it === existing }) candidates.add(0, existing)
                        for (candidate in candidates) {
                            val handle = findTouchedHandle(candidate, event.x, event.y)
                            if (handle != -1 || hitShapeBody(candidate, event.x, event.y)) {
                                if (candidate !== existing) {
                                    commitActiveShape()
                                    activeShape = candidate
                                    selectedShapeOriginal = candidate.copy()
                                }
                                draggedHandle = handle
                                isMovingShape = handle == -1
                                shapeDragStartX = x; shapeDragStartY = y
                                invalidate()
                                return true
                            }
                        }
                        commitActiveShape()

                        activeShape = AnnotationItem.Shape(
                            type = selectedShapeType,
                            startX = x,
                            startY = y,
                            endX = x,
                            endY = y,
                            color = activeColor,
                            strokeWidth = viewLengthToBase(activeStrokeWidth),
                            style = activeShapeStyle,
                            customShapeId = activeCustomShape?.id,
                            customPathData = activeCustomShape?.pathData,
                        )
                        annotations.add(requireNotNull(activeShape))
                        draggedHandle = 1
                        isMovingShape = false
                        onShapeStarted?.invoke()
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val shape = activeShape ?: return false
                        if (draggedHandle == 0) {
                            shape.startX = x
                            shape.startY = y
                        } else if (draggedHandle == 1) {
                            shape.endX = x
                            shape.endY = y
                        } else if (isMovingShape) {
                            val dx = x - shapeDragStartX; val dy = y - shapeDragStartY
                            shape.startX += dx; shape.startY += dy
                            shape.endX += dx; shape.endY += dy
                            shapeDragStartX = x; shapeDragStartY = y
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        draggedHandle = -1
                        isMovingShape = false
                        onAnnotationChanged?.invoke()
                        invalidate()
                        return true
                    }
                    else -> return false
                }
            }

            MarkupTool.TEXT -> {
                val t = activeText
                val hitRadius = viewLengthToBase(24f * resources.displayMetrics.density)

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (t != null) {
                            val bounds = RectF()
                            t.computeBounds(bounds)

                            val rotateY = bounds.top - viewLengthToBase(36f * resources.displayMetrics.density)
                            if (hypot((x - bounds.centerX()).toDouble(), (y - rotateY).toDouble()) <= hitRadius) {
                                isRotatingTextHandle = true
                                textInitialAngle = Math.toDegrees(atan2((y - t.centerY).toDouble(), (x - t.centerX).toDouble())).toFloat()
                                textBaseRotation = t.rotation
                                return true
                            }

                            // 1. Check resize handle at (bounds.right, bounds.bottom)
                            if (hypot((x - bounds.right).toDouble(), (y - bounds.bottom).toDouble()) <= hitRadius) {
                                isDraggingTextHandle = true
                                textInitialDist = hypot((x - t.centerX).toDouble(), (y - t.centerY).toDouble()).toFloat().coerceAtLeast(10f)
                                textInitialScale = t.scale
                                textInitialAngle = Math.toDegrees(atan2((y - t.centerY).toDouble(), (x - t.centerX).toDouble())).toFloat()
                                textBaseRotation = t.rotation
                                return true
                            }

                            // 2. Check tap/move inside bounds
                            if (t.contains(x, y)) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    pendingTextEdit = t
                                    lastTapTime = 0
                                    return true
                                }
                                lastTapTime = now
                                isMovingText = true
                                textDragStartX = x
                                textDragStartY = y
                                return true
                            }

                            // Tap outside: commit current text
                            commitActiveText()
                        }

                        // Previously committed items remain editable. Search from the topmost item.
                        val index = annotations.indexOfLast { item ->
                            item is AnnotationItem.Text && item.contains(x, y)
                        }
                        if (index >= 0) {
                            activeText = annotations[index] as AnnotationItem.Text
                            activeTextBackgroundColor = activeText!!.backgroundColor
                            hasTextBackgroundPill = activeText!!.hasBackgroundPill
                            activeTextFontId = activeText!!.fontId
                            activeTextAlignment = activeText!!.alignment
                            onTextSelectionChanged?.invoke()
                            isMovingText = true
                            textDragStartX = x
                            textDragStartY = y
                            lastTapTime = System.currentTimeMillis()
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        }

                        // Tap empty space: create new text item
                        val now = System.currentTimeMillis()
                        lastTapTime = now
                        val newItem = AnnotationItem.Text(
                            text = "",
                            centerX = x,
                            centerY = y,
                            baseTextSize = viewLengthToBase(32f),
                            color = activeColor or 0xFF000000.toInt(),
                            hasBackgroundPill = hasTextBackgroundPill,
                            backgroundColor = activeTextBackgroundColor,
                            fontId = activeTextFontId, alignment = activeTextAlignment,
                        )
                        activeText = newItem
                        invalidate()
                        pendingTextEdit = newItem
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {

                        val currentText = activeText ?: return false
                        if (isDraggingTextHandle) {
                            val currentDist = hypot((x - currentText.centerX).toDouble(), (y - currentText.centerY).toDouble()).toFloat()
                            val newScale = (currentDist / textInitialDist * textInitialScale).coerceIn(0.3f, 6.0f)
                            currentText.scale = newScale
                            constrainActiveText()
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        } else if (isRotatingTextHandle) {
                            val angle = Math.toDegrees(atan2((y - currentText.centerY).toDouble(), (x - currentText.centerX).toDouble())).toFloat()
                            currentText.rotation = textBaseRotation + angle - textInitialAngle
                            constrainActiveText()
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        } else if (isMovingText) {
                            val dx = x - textDragStartX
                            val dy = y - textDragStartY
                            currentText.centerX += dx
                            currentText.centerY += dy
                            constrainActiveText()
                            textDragStartX = x
                            textDragStartY = y
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        isDraggingTextHandle = false
                        isRotatingTextHandle = false
                        isMovingText = false
                        gestureSnapshot = null
                        pendingTextEdit?.let { onTextEditRequested?.invoke(it) }
                        pendingTextEdit = null
                    }
                }
                return true
            }

            MarkupTool.MOSAIC -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        currentMosaicWidth = viewLengthToBase(activeMosaicBrushSize)
                        val path = Path().apply { moveTo(x, y) }
                        currentMosaicPath = path
                        lastMosaicX = x
                        lastMosaicY = y
                        onMosaicStarted?.invoke()
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentMosaicPath?.let { path ->
                            val dx = kotlin.math.abs(x - lastMosaicX)
                            val dy = kotlin.math.abs(y - lastMosaicY)
                            if (dx >= 2 || dy >= 2) {
                                path.quadTo(lastMosaicX, lastMosaicY, (x + lastMosaicX) / 2f, (y + lastMosaicY) / 2f)
                                lastMosaicX = x
                                lastMosaicY = y
                                invalidate()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        currentMosaicPath = null
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        currentMosaicPath?.let { path ->
                            path.lineTo(x, y)
                            annotations.add(
                                AnnotationItem.Mosaic(
                                    path = path,
                                    brushSize = currentMosaicWidth,
                                    effect = activeMosaicEffect,
                                    cellSize = activeMosaicCellSize,
                                    color = Color.BLACK,
                                    customPattern = activeCustomMosaicPattern,
                                )
                            )
                            currentMosaicPath = null
                            onAnnotationChanged?.invoke()
                            invalidate()
                        }
                        return true
                    }
                    else -> return false
                }
            }

            MarkupTool.STICKER -> {
                val s = activeSticker
                val hitRadius = viewLengthToBase(24f * resources.displayMetrics.density)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (s != null) {
                            val bounds = RectF()
                            s.computeBounds(bounds)

                            // 1. Delete button at top-left
                            if (hypot((x - bounds.left).toDouble(), (y - bounds.top).toDouble()) <= hitRadius) {
                                pendingStickerDelete = true
                                return true
                            }

                            // 2. Resize & rotate handle at bottom-right
                            if (hypot((x - bounds.right).toDouble(), (y - bounds.bottom).toDouble()) <= hitRadius) {
                                isDraggingStickerHandle = true
                                stickerInitialDist = hypot((x - s.centerX).toDouble(), (y - s.centerY).toDouble()).toFloat().coerceAtLeast(10f)
                                stickerInitialScale = s.scale
                                stickerInitialAngle = (atan2((y - s.centerY).toDouble(), (x - s.centerX).toDouble()) * 180.0 / Math.PI).toFloat()
                                stickerBaseRotation = s.rotation
                                return true
                            }

                            // 3. Move inside bounds
                            if (s.contains(x, y)) {
                                isMovingSticker = true
                                stickerDragStartX = x
                                stickerDragStartY = y
                                return true
                            }

                            // Tap outside: commit current sticker
                            commitActiveSticker()
                        }
                        val index = annotations.indexOfLast { item ->
                            item is AnnotationItem.Sticker && item.contains(x, y)
                        }
                        if (index >= 0) {
                            activeSticker = annotations[index] as AnnotationItem.Sticker
                            isMovingSticker = true
                            stickerDragStartX = x
                            stickerDragStartY = y
                            onAnnotationChanged?.invoke()
                            invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val curr = activeSticker ?: return false
                        if (isDraggingStickerHandle) {
                            val currDist = hypot((x - curr.centerX).toDouble(), (y - curr.centerY).toDouble()).toFloat()
                            curr.scale = (currDist / stickerInitialDist * stickerInitialScale).coerceIn(0.2f, 6.0f)

                            val currAngle = (atan2((y - curr.centerY).toDouble(), (x - curr.centerX).toDouble()) * 180.0 / Math.PI).toFloat()
                            curr.rotation = stickerBaseRotation + (currAngle - stickerInitialAngle)
                            constrainActiveSticker()
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        } else if (isMovingSticker) {
                            val dx = x - stickerDragStartX
                            val dy = y - stickerDragStartY
                            curr.centerX += dx
                            curr.centerY += dy
                            constrainActiveSticker()
                            stickerDragStartX = x
                            stickerDragStartY = y
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (pendingStickerDelete) {
                            activeSticker?.let { sticker -> annotations.removeAll { it === sticker } }
                            activeSticker = null
                            pendingStickerDelete = false
                            onAnnotationChanged?.invoke()
                            invalidate()
                        }
                        gestureSnapshot = null
                        isDraggingStickerHandle = false
                        isMovingSticker = false
                    }
                }
                return true
            }

            else -> return false
        }
    }

    /** baseToOutput maps canonical bitmap coordinates into the output bitmap once. */
    fun rasterize(canvas: Canvas, baseToOutput: Matrix) {
        commitActiveShape()
        commitActiveText()
        commitActiveSticker()
        if (annotations.isEmpty()) return
        canvas.save()
        canvas.concat(baseToOutput)
        baseBoundsProvider?.invoke()?.takeUnless { it.isEmpty }?.let { canvas.clipRect(it) }
        annotations.forEach { drawAnnotation(canvas, it) }
        canvas.restore()
    }
}
