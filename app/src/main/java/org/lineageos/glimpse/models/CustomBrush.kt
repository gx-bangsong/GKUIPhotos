/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

enum class BrushColorMode {
    TINT,
    ORIGINAL,
}

data class CustomBrush(
    val id: String,
    val name: String,
    val tipFile: String,
    var spacingPercent: Int = 25,
    var sizeDp: Float = 24f,
    var opacity: Int = 100,
    var followDirection: Boolean = false,
    var colorMode: BrushColorMode = BrushColorMode.TINT,
) {
    fun loadTipBitmap(): Bitmap? = runCatching {
        if (File(tipFile).exists()) {
            BitmapFactory.decodeFile(tipFile)
        } else null
    }.getOrNull()

    companion object {
        fun detectColorMode(bitmap: Bitmap): BrushColorMode {
            val width = bitmap.width
            val height = bitmap.height
            val stepX = max(1, width / 20)
            val stepY = max(1, height / 20)
            var coloredPixels = 0
            var visiblePixels = 0

            for (y in 0 until height step stepY) {
                for (x in 0 until width step stepX) {
                    val pixel = bitmap.getPixel(x, y)
                    val a = Color.alpha(pixel)
                    if (a > 20) {
                        visiblePixels++
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val maxDiff = max(abs(r - g), max(abs(r - b), abs(g - b)))
                        if (maxDiff > 25) {
                            coloredPixels++
                        }
                    }
                }
            }
            return if (visiblePixels > 0 && (coloredPixels.toFloat() / visiblePixels) > 0.05f) {
                BrushColorMode.ORIGINAL
            } else {
                BrushColorMode.TINT
            }
        }

        fun serializeList(brushes: List<CustomBrush>): String {
            return brushes.joinToString("\n") { b ->
                "${b.id}\t${b.name}\t${b.tipFile}\t${b.spacingPercent}\t${b.sizeDp}\t${b.opacity}\t${b.followDirection}\t${b.colorMode.name}"
            }
        }

        fun deserializeList(content: String?): List<CustomBrush> {
            if (content.isNullOrBlank()) return emptyList()
            val list = mutableListOf<CustomBrush>()
            for (line in content.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 3) {
                    val id = parts[0]
                    val name = parts[1]
                    val tipFile = parts[2]
                    val spacing = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(5, 200) ?: 25
                    val sizeDp = parts.getOrNull(4)?.toFloatOrNull()?.coerceIn(2f, 100f) ?: 24f
                    val opacity = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(10, 100) ?: 100
                    val followDir = parts.getOrNull(6)?.toBoolean() ?: false
                    val colorMode = parts.getOrNull(7)?.let {
                        runCatching { BrushColorMode.valueOf(it) }.getOrNull()
                    } ?: BrushColorMode.TINT
                    list.add(CustomBrush(id, name, tipFile, spacing, sizeDp, opacity, followDir, colorMode))
                }
            }
            return list
        }
    }
}

/**
 * Pure Kotlin data structure for a sampled stamp point along a stroke in image coordinates.
 */
data class StampPoint(
    val x: Float,
    val y: Float,
    val angle: Float = 0f,
)

/**
 * Pure Kotlin stroke sampler that walks polyline segments with uniform spacing
 * and carries over remainder distance between segments.
 */
class StampStrokeSampler(
    val step: Float,
    val followDirection: Boolean = false,
    val maxStamps: Int = MAX_STAMPS_PER_STROKE,
) {
    companion object {
        const val MAX_STAMPS_PER_STROKE = 2000
    }

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var remainder: Float = 0f
    private val _stamps = mutableListOf<StampPoint>()
    val stamps: List<StampPoint> get() = _stamps

    fun start(x: Float, y: Float): List<StampPoint> {
        _stamps.clear()
        remainder = 0f
        lastX = x
        lastY = y
        val initial = StampPoint(x, y, 0f)
        _stamps.add(initial)
        return listOf(initial)
    }

    fun moveTo(x: Float, y: Float): List<StampPoint> {
        val dx = x - lastX
        val dy = y - lastY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist <= 1e-6f) return emptyList()

        val angle = if (followDirection) {
            (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / Math.PI).toFloat()
        } else {
            0f
        }

        val newStamps = mutableListOf<StampPoint>()
        var walked = step - remainder

        while (walked <= dist && _stamps.size < maxStamps) {
            val t = walked / dist
            val px = lastX + dx * t
            val py = lastY + dy * t
            val pt = StampPoint(px, py, angle)
            _stamps.add(pt)
            newStamps.add(pt)
            walked += step
        }

        remainder = dist - (walked - step)
        lastX = x
        lastY = y
        return newStamps
    }

    fun addPoint(x: Float, y: Float): List<StampPoint> = moveTo(x, y)
}
