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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.lineageos.glimpse.models.BrushColorMode
import org.lineageos.glimpse.models.CustomBrush
import org.lineageos.glimpse.models.CustomShape
import org.lineageos.glimpse.models.StampPoint
import org.lineageos.glimpse.models.StampStrokeSampler
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
                    p.color = Color.argb(100, Color.red(color), Color.green(color), Color.blue(color))
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
                    gp.color = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))
                    canvas.drawPath(path, gp)

                    p.color = Color.WHITE
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
            tPaint.color = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))

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

    data class Text(
        var text: String,
        var centerX: Float,
        var centerY: Float,
        var baseTextSize: Float = 32f,
        var scale: Float = 1.0f,
        var color: Int = Color.WHITE,
        var hasBackgroundPill: Boolean = true,
    ) : AnnotationItem {
        val effectiveTextSize: Float
            get() = (baseTextSize * scale).coerceIn(12f, 240f)

        fun computeBounds(outRect: RectF) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = effectiveTextSize
                isFakeBoldText = true
            }
            val textWidth = paint.measureText(text.ifEmpty { " " })
            val fm = paint.fontMetrics
            val textHeight = fm.bottom - fm.top
            val padX = effectiveTextSize * 0.45f
            val padY = effectiveTextSize * 0.3f

            val w = textWidth + padX * 2f
            val h = textHeight + padY * 2f
            outRect.set(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
        }

        override fun draw(canvas: Canvas) {
            if (text.isBlank()) return

            val bounds = RectF()
            computeBounds(bounds)

            if (hasBackgroundPill) {
                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (this@Text.color == Color.WHITE) Color.argb(160, 0, 0, 0) else Color.argb(160, 255, 255, 255)
                    style = Paint.Style.FILL
                }
                val pillRadius = bounds.height() * 0.25f
                canvas.drawRoundRect(bounds, pillRadius, pillRadius, pillPaint)
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = effectiveTextSize
                color = this@Text.color
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val fm = paint.fontMetrics
            val baseline = bounds.centerY() - (fm.descent + fm.ascent) / 2f
            canvas.drawText(text, bounds.centerX(), baseline, paint)
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
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            outRect.set(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
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
    ) : AnnotationItem {
        override fun draw(canvas: Canvas) {
            // Handled via drawWithMatrix
        }

        fun drawWithMatrix(canvas: Canvas, imageMatrix: Matrix?, reuseMatrix: Matrix, reusePaint: Paint) {
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
            commitActiveShape()
            commitActiveText()
            commitActiveSticker()
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

    var activeColor: Int = Color.RED
    var activeStrokeWidth: Float = 8f
    var hasTextBackgroundPill: Boolean = true
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

    private fun getMatrixScale(matrix: Matrix?): Float {
        if (matrix == null) return 1f
        val values = FloatArray(9)
        matrix.getValues(values)
        val sx = values[Matrix.MSCALE_X]
        val sy = values[Matrix.MSCALE_Y]
        return if (sx != 0f) abs(sx) else if (sy != 0f) abs(sy) else 1f
    }

    var onAnnotationChanged: (() -> Unit)? = null
    var onTextEditRequested: ((AnnotationItem.Text) -> Unit)? = null
    var onShapeStarted: (() -> Unit)? = null
    var onDoodleStarted: (() -> Unit)? = null
    var onMosaicStarted: (() -> Unit)? = null
    var cropRectProvider: (() -> RectF)? = null
    var imageBoundsProvider: (() -> RectF)? = null
    var displayBitmapProvider: (() -> Bitmap?)? = null
    var imageMatrixProvider: (() -> Matrix)? = null
    var isMarkupRestrictedToCrop: Boolean = false

    val annotations = mutableListOf<AnnotationItem>()

    // Current doodle in progress
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

    // Active uncommitted shape with handles
    var activeShape: AnnotationItem.Shape? = null
        private set

    // Active text item
    var activeText: AnnotationItem.Text? = null
        private set

    // Active sticker item
    var activeSticker: AnnotationItem.Sticker? = null
        private set

    // Handle dragging state
    private var draggedHandle: Int = -1 // 0: start, 1: end, -1: none
    private var isDraggingTextHandle: Boolean = false
    private var isMovingText: Boolean = false
    private var textDragStartX = 0f
    private var textDragStartY = 0f
    private var textInitialDist = 0f
    private var textInitialScale = 1f
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

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            activeText?.let { t ->
                t.scale = (t.scale * detector.scaleFactor).coerceIn(0.3f, 6.0f)
                onAnnotationChanged?.invoke()
                invalidate()
                return true
            }
            activeSticker?.let { s ->
                s.scale = (s.scale * detector.scaleFactor).coerceIn(0.2f, 6.0f)
                onAnnotationChanged?.invoke()
                invalidate()
                return true
            }
            return false
        }
    })

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
            imageMatrixProvider?.invoke()?.let { cached.setLocalMatrix(it) }
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
        imageMatrixProvider?.invoke()?.let { shader.setLocalMatrix(it) }
        mosaicShaderCache[cellSize] = shader
        return shader
    }

    private fun getOrCreateBlurShader(sourceBitmap: Bitmap? = null): BitmapShader? {
        val bmp = sourceBitmap ?: displayBitmapProvider?.invoke() ?: return null
        if (sourceBitmap == null && blurShader != null) {
            imageMatrixProvider?.invoke()?.let { blurShader?.setLocalMatrix(it) }
            return blurShader
        }

        val downW = max(4, bmp.width / 16)
        val downH = max(4, bmp.height / 16)
        val small = Bitmap.createScaledBitmap(bmp, downW, downH, true)
        val blurred = Bitmap.createScaledBitmap(small, bmp.width, bmp.height, true)
        if (small !== bmp && small !== blurred) small.recycle()
        val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (sourceBitmap == null) {
            imageMatrixProvider?.invoke()?.let { shader.setLocalMatrix(it) }
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
        val s = activeShape ?: return
        annotations.add(s)
        activeShape = null
        draggedHandle = -1
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun commitActiveText() {
        val t = activeText ?: return
        if (t.text.isNotBlank()) {
            annotations.add(t)
        }
        activeText = null
        isDraggingTextHandle = false
        isMovingText = false
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun commitActiveSticker() {
        val s = activeSticker ?: return
        annotations.add(s)
        activeSticker = null
        isDraggingStickerHandle = false
        isMovingSticker = false
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun addSticker(bmp: Bitmap) {
        commitActiveShape()
        commitActiveText()
        commitActiveSticker()

        activeTool = MarkupTool.STICKER
        val bounds = cropRectProvider?.invoke()?.takeUnless { it.isEmpty }
            ?: imageBoundsProvider?.invoke()
        val cx = bounds?.centerX() ?: (width / 2f)
        val cy = bounds?.centerY() ?: (height / 2f)
        val density = resources.displayMetrics.density
        val targetSize = minOf(130f * density, (bounds?.width() ?: width.toFloat()) * 0.7f,
            (bounds?.height() ?: height.toFloat()) * 0.7f)
        val maxDim = max(bmp.width, bmp.height).toFloat()
        val initialScale = if (maxDim > 0f) (targetSize / maxDim).coerceAtMost(3.0f) else 1f

        activeSticker = AnnotationItem.Sticker(
            bitmap = bmp,
            centerX = cx,
            centerY = cy,
            scale = initialScale,
        )
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun addTextItem(text: String, cx: Float, cy: Float) {
        commitActiveShape()
        commitActiveText()
        commitActiveSticker()
        activeTool = MarkupTool.TEXT
        val item = AnnotationItem.Text(
            text = text,
            centerX = cx,
            centerY = cy,
            color = activeColor,
            hasBackgroundPill = hasTextBackgroundPill,
        )
        activeText = item
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun undo(): Boolean {
        if (activeShape != null) {
            activeShape = null
            draggedHandle = -1
            invalidate()
            onAnnotationChanged?.invoke()
            return true
        }
        if (activeText != null) {
            activeText = null
            invalidate()
            onAnnotationChanged?.invoke()
            return true
        }
        if (activeSticker != null) {
            activeSticker = null
            invalidate()
            onAnnotationChanged?.invoke()
            return true
        }
        if (annotations.isNotEmpty()) {
            annotations.removeAt(annotations.size - 1)
            invalidate()
            onAnnotationChanged?.invoke()
            return true
        }
        return false
    }

    fun clear() {
        activeShape = null
        activeText = null
        activeSticker = null
        draggedHandle = -1
        annotations.clear()
        invalidate()
        onAnnotationChanged?.invoke()
    }

    fun hasAnnotations(): Boolean = annotations.isNotEmpty() || activeShape != null || activeText != null || activeSticker != null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bounds = if (isMarkupRestrictedToCrop) {
            cropRectProvider?.invoke()
        } else {
            imageBoundsProvider?.invoke() ?: cropRectProvider?.invoke()
        }
        if (bounds != null && !bounds.isEmpty) {
            canvas.save()
            canvas.clipRect(bounds)
        }

        val currentMatrix = imageMatrixProvider?.invoke()

        // 1. Draw committed annotations
        for (item in annotations) {
            if (item is AnnotationItem.StampStroke) {
                item.drawWithMatrix(canvas, currentMatrix, stampDrawMatrix, stampDrawPaint)
            } else if (item is AnnotationItem.Mosaic) {
                if (item.path == null) continue
                when (item.effect) {
                    MosaicEffect.PIXELATE -> {
                        val shader = getOrCreateMosaicShader(item.cellSize)
                        if (shader != null) {
                            if (currentMatrix != null) shader.setLocalMatrix(currentMatrix)
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                this.shader = shader
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                strokeWidth = item.brushSize
                                isFilterBitmap = false
                            }
                            canvas.drawPath(item.path, paint)
                        }
                    }
                    MosaicEffect.BLUR -> {
                        val shader = getOrCreateBlurShader()
                        if (shader != null) {
                            if (currentMatrix != null) shader.setLocalMatrix(currentMatrix)
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                this.shader = shader
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                strokeWidth = item.brushSize
                                isFilterBitmap = true
                            }
                            canvas.drawPath(item.path, paint)
                        }
                    }
                    MosaicEffect.BLACKOUT -> {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = item.color
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = item.brushSize
                        }
                        canvas.drawPath(item.path, paint)
                    }
                    MosaicEffect.STRIPES -> {
                        val shader = getOrCreateStripesShader()
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            this.shader = shader
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = item.brushSize
                        }
                        canvas.drawPath(item.path, paint)
                    }
                    MosaicEffect.NOISE -> {
                        val shader = getOrCreateNoiseShader()
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            this.shader = shader
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = item.brushSize
                        }
                        canvas.drawPath(item.path, paint)
                    }
                    MosaicEffect.CUSTOM_PATTERN -> {
                        val pattern = item.customPattern
                        if (pattern != null && !pattern.isRecycled) {
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                strokeWidth = item.brushSize
                            }
                            canvas.drawPath(item.path, paint)
                        }
                    }
                }
            } else {
                item.draw(canvas)
            }
        }

        // 2. Draw active doodle in progress
        val inProgressSampler = currentStampSampler
        val activeBrush = activeCustomBrush
        val activeTip = activeCustomBrushTipBitmap
        if (inProgressSampler != null && activeBrush != null && activeTip != null && !activeTip.isRecycled) {
            val stroke = AnnotationItem.StampStroke(
                brushId = activeBrush.id,
                points = inProgressSampler.stamps,
                sizeInImage = currentStampSizeInImage,
                sizeDp = activeBrush.sizeDp,
                color = activeColor,
                opacity = activeBrush.opacity,
                colorMode = activeBrush.colorMode,
                tipBitmap = activeTip,
            )
            stroke.drawWithMatrix(canvas, currentMatrix, stampDrawMatrix, stampDrawPaint)
        } else {
            currentDoodlePath?.let { path ->
                val doodle = AnnotationItem.Doodle(
                    path = path,
                    color = activeColor,
                    strokeWidth = activeStrokeWidth,
                    brushType = activeBrushType,
                    customTexture = activeCustomBrushTexture,
                )
                doodle.draw(canvas)
            }
        }

        // 3. Draw active mosaic in progress
        currentMosaicPath?.let { path ->
            when (activeMosaicEffect) {
                MosaicEffect.PIXELATE -> {
                    val shader = getOrCreateMosaicShader(activeMosaicCellSize)
                    if (shader != null) {
                        if (currentMatrix != null) shader.setLocalMatrix(currentMatrix)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            this.shader = shader
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = activeMosaicBrushSize
                            isFilterBitmap = false
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                MosaicEffect.BLUR -> {
                    val shader = getOrCreateBlurShader()
                    if (shader != null) {
                        if (currentMatrix != null) shader.setLocalMatrix(currentMatrix)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            this.shader = shader
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = activeMosaicBrushSize
                            isFilterBitmap = true
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                MosaicEffect.BLACKOUT -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = activeMosaicBrushSize
                    }
                    canvas.drawPath(path, paint)
                }
                MosaicEffect.STRIPES -> {
                    val shader = getOrCreateStripesShader()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.shader = shader
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = activeMosaicBrushSize
                    }
                    canvas.drawPath(path, paint)
                }
                MosaicEffect.NOISE -> {
                    val shader = getOrCreateNoiseShader()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.shader = shader
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = activeMosaicBrushSize
                    }
                    canvas.drawPath(path, paint)
                }
                MosaicEffect.CUSTOM_PATTERN -> {
                    val pattern = activeCustomMosaicPattern
                    if (pattern != null && !pattern.isRecycled) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = activeMosaicBrushSize
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }

        // 4. Draw active shape and handles
        activeShape?.let { shape ->
            shape.draw(canvas)
            drawHandle(canvas, shape.startX, shape.startY)
            drawHandle(canvas, shape.endX, shape.endY)
        }

        // 5. Draw active text with bounding outline and resize handle
        activeText?.let { t ->
            t.draw(canvas)
            val textBounds = RectF()
            t.computeBounds(textBounds)
            canvas.drawRoundRect(textBounds, 8f, 8f, boundsDashPaint)
            drawHandle(canvas, textBounds.right, textBounds.bottom)
        }

        // 6. Draw active sticker with bounding box, handle and delete button
        activeSticker?.let { s ->
            s.draw(canvas)
            val stickerBounds = RectF()
            s.computeBounds(stickerBounds)
            canvas.drawRoundRect(stickerBounds, 8f, 8f, boundsDashPaint)
            drawHandle(canvas, stickerBounds.right, stickerBounds.bottom)
            drawDeleteBadge(canvas, stickerBounds.left, stickerBounds.top)
        }

        if (bounds != null && !bounds.isEmpty) {
            canvas.restore()
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val radius = 16f
        canvas.drawCircle(x, y, radius, handlePaint)
        canvas.drawCircle(x, y, radius, handleBorderPaint)
    }

    private fun drawDeleteBadge(canvas: Canvas, x: Float, y: Float) {
        val radius = 16f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 3f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(x, y, radius, bgPaint)
        val d = 6f
        canvas.drawLine(x - d, y - d, x + d, y + d, xPaint)
        canvas.drawLine(x - d, y + d, x + d, y - d, xPaint)
    }

    private fun findTouchedHandle(shape: AnnotationItem.Shape, x: Float, y: Float): Int {
        val hitRadius = 48f
        if (hypot((x - shape.startX).toDouble(), (y - shape.startY).toDouble()) <= hitRadius) return 0
        if (hypot((x - shape.endX).toDouble(), (y - shape.endY).toDouble()) <= hitRadius) return 1
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (activeTool == MarkupTool.NONE) return false

        if (activeTool == MarkupTool.TEXT || activeTool == MarkupTool.STICKER) {
            scaleDetector.onTouchEvent(event)
        }

        val x = event.x
        val y = event.y

        when (activeTool) {
            MarkupTool.DOODLE -> {
                val customBrush = activeCustomBrush
                if (customBrush != null) {
                    val invMatrix = Matrix()
                    imageMatrixProvider?.invoke()?.invert(invMatrix)
                    val pts = floatArrayOf(x, y)
                    invMatrix.mapPoints(pts)
                    val imgX = pts[0]
                    val imgY = pts[1]

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
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val sampler = currentStampSampler
                            val bmp = activeCustomBrushTipBitmap
                            if (sampler != null && sampler.stamps.isNotEmpty() && bmp != null && !bmp.isRecycled) {
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
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            currentDoodlePath?.let { path ->
                                path.lineTo(x, y)
                                annotations.add(
                                    AnnotationItem.Doodle(
                                        path = path,
                                        color = activeColor,
                                        strokeWidth = activeStrokeWidth,
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
                        if (existing != null) {
                            val handleIdx = findTouchedHandle(existing, x, y)
                            if (handleIdx != -1) {
                                draggedHandle = handleIdx
                                return true
                            } else {
                                commitActiveShape()
                            }
                        }

                        activeShape = AnnotationItem.Shape(
                            type = selectedShapeType,
                            startX = x,
                            startY = y,
                            endX = x,
                            endY = y,
                            color = activeColor,
                            strokeWidth = activeStrokeWidth,
                            style = activeShapeStyle,
                            customShapeId = activeCustomShape?.id,
                            customPathData = activeCustomShape?.pathData,
                        )
                        draggedHandle = 1
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
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        draggedHandle = -1
                        onAnnotationChanged?.invoke()
                        invalidate()
                        return true
                    }
                    else -> return false
                }
            }

            MarkupTool.TEXT -> {
                val t = activeText
                val hitRadius = 48f

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (t != null) {
                            val bounds = RectF()
                            t.computeBounds(bounds)

                            // 1. Check resize handle at (bounds.right, bounds.bottom)
                            if (hypot((x - bounds.right).toDouble(), (y - bounds.bottom).toDouble()) <= hitRadius) {
                                isDraggingTextHandle = true
                                textInitialDist = hypot((x - t.centerX).toDouble(), (y - t.centerY).toDouble()).toFloat().coerceAtLeast(10f)
                                textInitialScale = t.scale
                                return true
                            }

                            // 2. Check tap/move inside bounds
                            if (bounds.contains(x, y)) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    onTextEditRequested?.invoke(t)
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
                            item is AnnotationItem.Text && RectF().also { item.computeBounds(it) }.contains(x, y)
                        }
                        if (index >= 0) {
                            activeText = annotations.removeAt(index) as AnnotationItem.Text
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
                            color = activeColor,
                            hasBackgroundPill = hasTextBackgroundPill,
                        )
                        activeText = newItem
                        invalidate()
                        onTextEditRequested?.invoke(newItem)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (scaleDetector.isInProgress) return true

                        val currentText = activeText ?: return false
                        if (isDraggingTextHandle) {
                            val currentDist = hypot((x - currentText.centerX).toDouble(), (y - currentText.centerY).toDouble()).toFloat()
                            val newScale = (currentDist / textInitialDist * textInitialScale).coerceIn(0.3f, 6.0f)
                            currentText.scale = newScale
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        } else if (isMovingText) {
                            val dx = x - textDragStartX
                            val dy = y - textDragStartY
                            currentText.centerX += dx
                            currentText.centerY += dy
                            textDragStartX = x
                            textDragStartY = y
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDraggingTextHandle = false
                        isMovingText = false
                    }
                }
                return true
            }

            MarkupTool.MOSAIC -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentMosaicPath?.let { path ->
                            path.lineTo(x, y)
                            annotations.add(
                                AnnotationItem.Mosaic(
                                    path = path,
                                    brushSize = activeMosaicBrushSize,
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
                val hitRadius = 48f
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (s != null) {
                            val bounds = RectF()
                            s.computeBounds(bounds)

                            // 1. Delete button at top-left
                            if (hypot((x - bounds.left).toDouble(), (y - bounds.top).toDouble()) <= hitRadius) {
                                activeSticker = null
                                onAnnotationChanged?.invoke()
                                invalidate()
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
                            if (bounds.contains(x, y)) {
                                isMovingSticker = true
                                stickerDragStartX = x
                                stickerDragStartY = y
                                return true
                            }

                            // Tap outside: commit current sticker
                            commitActiveSticker()
                        }
                        val index = annotations.indexOfLast { item ->
                            item is AnnotationItem.Sticker && RectF().also { item.computeBounds(it) }.contains(x, y)
                        }
                        if (index >= 0) {
                            activeSticker = annotations.removeAt(index) as AnnotationItem.Sticker
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
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        } else if (isMovingSticker) {
                            val dx = x - stickerDragStartX
                            val dy = y - stickerDragStartY
                            curr.centerX += dx
                            curr.centerY += dy
                            stickerDragStartX = x
                            stickerDragStartY = y
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDraggingStickerHandle = false
                        isMovingSticker = false
                    }
                }
                return true
            }

            else -> return false
        }
    }

    /**
     * Rasterize all annotations onto a target canvas mapped relative to the visible crop rectangle.
     */
    fun rasterize(canvas: Canvas, cropRect: RectF, exportBitmap: Bitmap? = null) {
        commitActiveShape()
        commitActiveText()
        commitActiveSticker()
        if (annotations.isEmpty()) return
        if (cropRect.isEmpty) return

        canvas.save()
        val scaleX = canvas.width.toFloat() / cropRect.width()
        val scaleY = canvas.height.toFloat() / cropRect.height()
        canvas.scale(scaleX, scaleY)
        canvas.translate(-cropRect.left, -cropRect.top)

        // Precompute export mosaic shaders if exportBitmap is provided
        val exportShaders = mutableMapOf<Int, BitmapShader>()
        val mosaicItems = annotations.filterIsInstance<AnnotationItem.Mosaic>()
        if (exportBitmap != null && mosaicItems.isNotEmpty()) {
            val cellSizes = mosaicItems.filter { it.effect == MosaicEffect.PIXELATE }.map { it.cellSize }.distinct()
            for (cs in cellSizes) {
                val clampedCs = cs.coerceAtLeast(2)
                val sW = max(1, exportBitmap.width / clampedCs)
                val sH = max(1, exportBitmap.height / clampedCs)
                val sBmp = Bitmap.createScaledBitmap(exportBitmap, sW, sH, false)
                val mBmp = Bitmap.createScaledBitmap(sBmp, exportBitmap.width, exportBitmap.height, false)
                if (sBmp !== mBmp && sBmp !== exportBitmap) sBmp.recycle()
                exportShaders[cs] = BitmapShader(mBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        }

        val exportBlurShader = if (exportBitmap != null && mosaicItems.any { it.effect == MosaicEffect.BLUR }) {
            getOrCreateBlurShader(exportBitmap)
        } else null

        for (item in annotations) {
            if (item is AnnotationItem.StampStroke) {
                val matrix = imageMatrixProvider?.invoke()
                item.drawWithMatrix(canvas, matrix, stampDrawMatrix, stampDrawPaint)
            } else if (item is AnnotationItem.Mosaic) {
                if (item.path == null) continue
                when (item.effect) {
                    MosaicEffect.PIXELATE -> {
                        val shader = exportShaders[item.cellSize] ?: getOrCreateMosaicShader(item.cellSize)
                        if (shader != null) {
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                this.shader = shader
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                strokeWidth = item.brushSize
                                isFilterBitmap = false
                            }
                            canvas.drawPath(item.path, paint)
                        }
                    }
                    MosaicEffect.BLUR -> {
                        val shader = exportBlurShader ?: getOrCreateBlurShader()
                        if (shader != null) {
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                this.shader = shader
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                strokeWidth = item.brushSize
                                isFilterBitmap = true
                            }
                            canvas.drawPath(item.path, paint)
                        }
                    }
                    MosaicEffect.BLACKOUT -> {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = item.color
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = item.brushSize
                        }
                        canvas.drawPath(item.path, paint)
                    }
                    MosaicEffect.STRIPES -> {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = getOrCreateStripesShader()
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = item.brushSize
                        }
                        canvas.drawPath(item.path, paint)
                    }
                    MosaicEffect.NOISE -> {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = getOrCreateNoiseShader()
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            strokeWidth = item.brushSize
                        }
                        canvas.drawPath(item.path, paint)
                    }
                    MosaicEffect.CUSTOM_PATTERN -> {
                        val pattern = item.customPattern
                        if (pattern != null && !pattern.isRecycled) {
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                strokeWidth = item.brushSize
                            }
                            canvas.drawPath(item.path, paint)
                        }
                    }
                }
            } else {
                item.draw(canvas)
            }
        }

        canvas.restore()
    }
}
