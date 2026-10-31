/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed class MattingResult {
    data class Success(val bitmap: Bitmap) : MattingResult()
    data class Refused(val reason: String) : MattingResult()
}

sealed class MattingPixelsResult {
    data class Success(val pixels: IntArray, val width: Int, val height: Int) : MattingPixelsResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
        }

        override fun hashCode(): Int {
            var result = pixels.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }

    data class Refused(val reason: String) : MattingPixelsResult()
}

data class BackgroundStats(
    val meanY: Float,
    val meanCb: Float,
    val meanCr: Float,
    val sigmaChroma: Float,
    val gradientMean: Float,
    val isPurityAcceptable: Boolean,
)

/**
 * Solid-color portrait matting engine for ID photo background replacement.
 * Follows spec: docs/ID_PHOTO_PLAN.md §4.4.
 *
 * Zero new dependencies: pure BT.601 YCbCr ellipsoid distance keying,
 * border purity sampling with graceful refusal, dual-threshold smoothstep soft matte,
 * and color spill suppression (de-fringing).
 */
object SolidColorMattingEngine {

    // Calibration-ready parameters (§4.4.1 - §4.4.3)
    const val DEFAULT_LAMBDA = 0.15f // calibration pending — owner device testing
    const val DEFAULT_T_INNER = 12.0f // calibration pending — owner device testing
    // Calibration note: To fix color halo, INCREASE T_outer (e.g. 28 -> 32-34) to pull fringe pixels into transition band; do NOT shrink.
    const val DEFAULT_T_OUTER = 28.0f // calibration pending — owner device testing
    const val DEFAULT_CHROMA_STD_DEV_THRESHOLD = 14.0f // calibration pending — owner device testing
    const val DEFAULT_SOBEL_GRADIENT_MEAN_THRESHOLD = 25.0f // calibration pending — owner device testing

    /**
     * Threshold for central region separability pre-check (§4.4.1 extension).
     * If the fraction of pixels in the center 50%x50% box with D < T_outer exceeds this value,
     * the subject color is deemed too close to the background (e.g. white shirt on white background).
     */
    const val DEFAULT_SEPARABILITY_THRESHOLD = 0.12f // calibration pending — owner device testing

    /**
     * Maximum dimension of the downscaled analysis grid for flood-fill connectivity constraint.
     */
    const val CONNECTIVITY_ANALYSIS_MAX_DIM = 512

    /**
     * Internal debug toggle that overlays the matte mask on the preview
     * (owner calibration aid; not reachable from normal UI).
     */
    var DEBUG_OVERLAY_MATTE_MASK: Boolean = false

    const val REFUSAL_MESSAGE =
        "检测到背景色彩不均匀，当前仅支持纯色背景证件照换底。已为您保留原背景继续生成。"

    const val REFUSAL_MESSAGE_SEPARABILITY =
        "人物与背景颜色过于接近，无法安全换底。已为您保留原背景继续生成。"

    /**
     * Convert RGB (0..255) to ITU-R BT.601 YCbCr.
     * Y:  0.299*R + 0.587*G + 0.114*B
     * Cb: -0.1687*R - 0.3313*G + 0.5*B + 128
     * Cr: 0.5*R - 0.4187*G - 0.0813*B + 128
     */
    fun rgbToYcbcr(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = -0.1687f * r - 0.3313f * g + 0.5f * b + 128.0f
        val cr = 0.5f * r - 0.4187f * g - 0.0813f * b + 128.0f
        return Triple(y, cb, cr)
    }

    /**
     * Sample border regions (§4.4.1):
     * - Top 5% rows: [0 until topH]
     * - Left 5% cols over top 70% height: [0 until sideW] x [0 until sideH]
     * - Right 5% cols over top 70% height: [w - sideW until w] x [0 until sideH]
     */
    fun sampleBorderStatsFromPixels(pixels: IntArray, w: Int, h: Int): BackgroundStats {
        val topH = (h * 0.05f).toInt().coerceIn(1, h)
        val sideH = (h * 0.70f).toInt().coerceIn(1, h)
        val sideW = (w * 0.05f).toInt().coerceIn(1, w)

        var sumY = 0.0
        var sumCb = 0.0
        var sumCr = 0.0
        var count = 0

        // Pass 1: compute sums for means
        for (y in 0 until sideH) {
            val yOffset = y * w
            val isTop = y < topH
            for (x in 0 until w) {
                if (isTop || x < sideW || x >= w - sideW) {
                    val p = pixels[yOffset + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                    val cbVal = -0.1687f * r - 0.3313f * g + 0.5f * b + 128.0f
                    val crVal = 0.5f * r - 0.4187f * g - 0.0813f * b + 128.0f

                    sumY += yVal
                    sumCb += cbVal
                    sumCr += crVal
                    count++
                }
            }
        }

        if (count == 0) {
            return BackgroundStats(255f, 128f, 128f, 0f, 0f, true)
        }

        val meanY = (sumY / count).toFloat()
        val meanCb = (sumCb / count).toFloat()
        val meanCr = (sumCr / count).toFloat()

        // Pass 2: compute chroma variance and Sobel gradient
        var varCbSum = 0.0
        var varCrSum = 0.0

        var gradSum = 0.0
        var gradCount = 0

        for (y in 0 until sideH) {
            val yOffset = y * w
            val isTop = y < topH
            for (x in 0 until w) {
                if (isTop || x < sideW || x >= w - sideW) {
                    val p = pixels[yOffset + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val cbVal = -0.1687f * r - 0.3313f * g + 0.5f * b + 128.0f
                    val crVal = 0.5f * r - 0.4187f * g - 0.0813f * b + 128.0f

                    val dCb = cbVal - meanCb
                    val dCr = crVal - meanCr
                    varCbSum += dCb * dCb
                    varCrSum += dCr * dCr

                    // Sobel gradient if interior neighbor exists
                    if (x in 1 until w - 1 && y in 1 until h - 1) {
                        fun lum(px: Int, py: Int): Float {
                            val neighbor = pixels[py * w + px]
                            val nr = (neighbor shr 16) and 0xFF
                            val ng = (neighbor shr 8) and 0xFF
                            val nb = neighbor and 0xFF
                            return 0.299f * nr + 0.587f * ng + 0.114f * nb
                        }

                        val gx = (lum(x + 1, y - 1) + 2f * lum(x + 1, y) + lum(x + 1, y + 1)) -
                                (lum(x - 1, y - 1) + 2f * lum(x - 1, y) + lum(x - 1, y + 1))
                        val gy = (lum(x - 1, y + 1) + 2f * lum(x, y + 1) + lum(x + 1, y + 1)) -
                                (lum(x - 1, y - 1) + 2f * lum(x, y - 1) + lum(x + 1, y - 1))

                        val grad = sqrt(gx * gx + gy * gy) / 4.0f
                        gradSum += grad
                        gradCount++
                    }
                }
            }
        }

        val sigmaCb = sqrt(varCbSum / count).toFloat()
        val sigmaCr = sqrt(varCrSum / count).toFloat()
        val sigmaChroma = sqrt(sigmaCb * sigmaCb + sigmaCr * sigmaCr)
        val gradientMean = if (gradCount > 0) (gradSum / gradCount).toFloat() else 0f

        val acceptable = sigmaChroma <= DEFAULT_CHROMA_STD_DEV_THRESHOLD &&
                gradientMean <= DEFAULT_SOBEL_GRADIENT_MEAN_THRESHOLD

        return BackgroundStats(
            meanY = meanY,
            meanCb = meanCb,
            meanCr = meanCr,
            sigmaChroma = sigmaChroma,
            gradientMean = gradientMean,
            isPurityAcceptable = acceptable,
        )
    }

    fun sampleBorderStats(bitmap: Bitmap): BackgroundStats {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return sampleBorderStatsFromPixels(pixels, w, h)
    }

    /**
     * Compute YCbCr ellipsoid distance D(p) per §4.4.2:
     * D(p) = sqrt(ΔCb² + ΔCr² + λ·ΔY²)
     */
    fun computeDistance(
        r: Int, g: Int, b: Int,
        bgY: Float, bgCb: Float, bgCr: Float,
        lambda: Float = DEFAULT_LAMBDA,
    ): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = -0.1687f * r - 0.3313f * g + 0.5f * b + 128.0f
        val cr = 0.5f * r - 0.4187f * g - 0.0813f * b + 128.0f

        val dy = y - bgY
        val dcb = cb - bgCb
        val dcr = cr - bgCr

        return sqrt(dcb * dcb + dcr * dcr + lambda * dy * dy)
    }

    /**
     * Dual-threshold soft matte + smoothstep feathering per §4.4.3:
     * - D(p) <= T_inner => alpha = 0.0
     * - D(p) >= T_outer => alpha = 1.0
     * - T_inner < D(p) < T_outer => t = (D - T_inner) / (T_outer - T_inner), alpha = 3t² - 2t³
     */
    fun computeMatteAlpha(
        d: Float,
        tInner: Float = DEFAULT_T_INNER,
        tOuter: Float = DEFAULT_T_OUTER,
    ): Float {
        if (d <= tInner) return 0f
        if (d >= tOuter) return 1f
        val t = (d - tInner) / (tOuter - tInner)
        return 3f * t * t - 2f * t * t * t
    }

    /**
     * Array-based solid-color matting algorithm core with spatial protection:
     * - Border purity detection (§4.4.1)
     * - Central separability pre-check (refusing white-shirt-on-white / subject-bg color clash)
     * - Flood-fill connectivity constraint from border seeds (protecting enclosed eye whites/catchlights)
     * - Dual-threshold smoothstep soft matte (§4.4.3)
     * - Color spill suppression (§4.4.4)
     */
    fun processPixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        targetColorInt: Int,
        lambda: Float = DEFAULT_LAMBDA,
        tInner: Float = DEFAULT_T_INNER,
        tOuter: Float = DEFAULT_T_OUTER,
        chromaThreshold: Float = DEFAULT_CHROMA_STD_DEV_THRESHOLD,
        gradientThreshold: Float = DEFAULT_SOBEL_GRADIENT_MEAN_THRESHOLD,
        separabilityThreshold: Float = DEFAULT_SEPARABILITY_THRESHOLD,
    ): MattingPixelsResult {
        val stats = sampleBorderStatsFromPixels(pixels, w, h)
        if (stats.sigmaChroma > chromaThreshold || stats.gradientMean > gradientThreshold) {
            return MattingPixelsResult.Refused(REFUSAL_MESSAGE)
        }

        val bgY = stats.meanY
        val bgCb = stats.meanCb
        val bgCr = stats.meanCr

        // Step 1: Separability pre-check in central region (center 50% x 50% box)
        val cxStart = w / 4
        val cxEnd = w - w / 4
        val cyStart = h / 4
        val cyEnd = h - h / 4
        val totalCenterPixels = (cxEnd - cxStart) * (cyEnd - cyStart)

        if (totalCenterPixels > 0) {
            var closeColorCount = 0
            for (cy in cyStart until cyEnd) {
                val rowOffset = cy * w
                for (cx in cxStart until cxEnd) {
                    val p = pixels[rowOffset + cx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val d = computeDistance(r, g, b, bgY, bgCb, bgCr, lambda)
                    if (d < tOuter) {
                        closeColorCount++
                    }
                }
            }
            val closeFraction = closeColorCount.toFloat() / totalCenterPixels.toFloat()
            if (closeFraction > separabilityThreshold) {
                return MattingPixelsResult.Refused(REFUSAL_MESSAGE_SEPARABILITY)
            }
        }

        // Step 2: Spatial connectivity analysis on downscaled grid (longest side <= 512px)
        val maxDim = maxOf(w, h)
        val scale = if (maxDim > CONNECTIVITY_ANALYSIS_MAX_DIM) CONNECTIVITY_ANALYSIS_MAX_DIM.toFloat() / maxDim else 1f
        val sw = if (scale < 1f) (w * scale).roundToInt().coerceIn(1, w) else w
        val sh = if (scale < 1f) (h * scale).roundToInt().coerceIn(1, h) else h

        val smallPixels = if (sw == w && sh == h) {
            pixels
        } else {
            val downsampled = IntArray(sw * sh)
            for (sy in 0 until sh) {
                val srcY = (sy.toLong() * h / sh).toInt().coerceIn(0, h - 1)
                val srcRow = srcY * w
                val dstRow = sy * sw
                for (sx in 0 until sw) {
                    val srcX = (sx.toLong() * w / sw).toInt().coerceIn(0, w - 1)
                    downsampled[dstRow + sx] = pixels[srcRow + srcX]
                }
            }
            downsampled
        }

        val isConnected = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        var head = 0
        var tail = 0

        val topH = (sh * 0.05f).toInt().coerceIn(1, sh)
        val sideH = (sh * 0.70f).toInt().coerceIn(1, sh)
        val sideW = (sw * 0.05f).toInt().coerceIn(1, sw)

        fun checkSeed(sx: Int, sy: Int) {
            val idx = sy * sw + sx
            if (!isConnected[idx]) {
                val p = smallPixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val d = computeDistance(r, g, b, bgY, bgCb, bgCr, lambda)
                if (d < tInner) {
                    isConnected[idx] = true
                    queue[tail++] = idx
                }
            }
        }

        for (sx in 0 until sw) {
            checkSeed(sx, 0)
            checkSeed(sx, sh - 1)
        }
        for (sy in 0 until sh) {
            checkSeed(0, sy)
            checkSeed(sw - 1, sy)
        }
        for (sy in 0 until sideH) {
            val isTop = sy < topH
            for (sx in 0 until sw) {
                if (isTop || sx < sideW || sx >= sw - sideW) {
                    checkSeed(sx, sy)
                }
            }
        }

        while (head < tail) {
            val curr = queue[head++]
            val cx = curr % sw
            val cy = curr / sw

            fun checkNeighbor(nx: Int, ny: Int) {
                val nIdx = ny * sw + nx
                if (!isConnected[nIdx]) {
                    val p = smallPixels[nIdx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val d = computeDistance(r, g, b, bgY, bgCb, bgCr, lambda)
                    if (d < tOuter) {
                        isConnected[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }

            if (cx > 0) checkNeighbor(cx - 1, cy)
            if (cx < sw - 1) checkNeighbor(cx + 1, cy)
            if (cy > 0) checkNeighbor(cx, cy - 1)
            if (cy < sh - 1) checkNeighbor(cx, cy + 1)
        }

        // Step 3: Full-resolution matting with connectivity constraint and feathering
        val outPixels = IntArray(w * h)

        val targetR = (targetColorInt shr 16) and 0xFF
        val targetG = (targetColorInt shr 8) and 0xFF
        val targetB = targetColorInt and 0xFF

        for (y in 0 until h) {
            val yOffset = y * w
            val sy = if (sh == h) y else (y.toLong() * sh / h).toInt().coerceIn(0, sh - 1)
            val sRowOffset = sy * sw

            for (x in 0 until w) {
                val i = yOffset + x
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                val cbVal = -0.1687f * r - 0.3313f * g + 0.5f * b + 128.0f
                val crVal = 0.5f * r - 0.4187f * g - 0.0813f * b + 128.0f

                val dy = yVal - bgY
                val dcb = cbVal - bgCb
                val dcr = crVal - bgCr
                val dist = sqrt(dcb * dcb + dcr * dcr + lambda * dy * dy)

                val sx = if (sw == w) x else (x.toLong() * sw / w).toInt().coerceIn(0, sw - 1)
                val isBorderConnected = isConnected[sRowOffset + sx]

                val alpha = if (!isBorderConnected) {
                    1f // Enclosed low-D pixels (eye whites, specular highlights) are forced alpha = 1
                } else if (dist <= tInner) {
                    0f
                } else if (dist >= tOuter) {
                    1f
                } else {
                    val t = (dist - tInner) / (tOuter - tInner)
                    3f * t * t - 2f * t * t * t
                }

                if (DEBUG_OVERLAY_MATTE_MASK) {
                    val maskVal = (alpha * 255f).roundToInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (maskVal shl 16) or (maskVal shl 8) or maskVal
                } else {
                    // Color spill suppression: in the transition band (0 < alpha < 1),
                    // desaturate foreground chroma towards luminance Y to eliminate old background halos
                    val destatR = r * alpha + yVal * (1f - alpha)
                    val destatG = g * alpha + yVal * (1f - alpha)
                    val destatB = b * alpha + yVal * (1f - alpha)

                    // Composite foreground over target background color
                    val outR = (destatR * alpha + targetR * (1f - alpha)).roundToInt().coerceIn(0, 255)
                    val outG = (destatG * alpha + targetG * (1f - alpha)).roundToInt().coerceIn(0, 255)
                    val outB = (destatB * alpha + targetB * (1f - alpha)).roundToInt().coerceIn(0, 255)

                    outPixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }

        return MattingPixelsResult.Success(outPixels, w, h)
    }

    /**
     * End-to-end solid-color matting pipeline operating on Android Bitmap.
     */
    fun process(
        source: Bitmap,
        targetColorInt: Int,
        lambda: Float = DEFAULT_LAMBDA,
        tInner: Float = DEFAULT_T_INNER,
        tOuter: Float = DEFAULT_T_OUTER,
        chromaThreshold: Float = DEFAULT_CHROMA_STD_DEV_THRESHOLD,
        gradientThreshold: Float = DEFAULT_SOBEL_GRADIENT_MEAN_THRESHOLD,
        separabilityThreshold: Float = DEFAULT_SEPARABILITY_THRESHOLD,
    ): MattingResult {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        return when (val res = processPixels(
            pixels, w, h, targetColorInt,
            lambda, tInner, tOuter, chromaThreshold, gradientThreshold, separabilityThreshold
        )) {
            is MattingPixelsResult.Success -> {
                val outputBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                outputBitmap.setPixels(res.pixels, 0, w, 0, 0, w, h)
                MattingResult.Success(outputBitmap)
            }
            is MattingPixelsResult.Refused -> MattingResult.Refused(res.reason)
        }
    }
}
