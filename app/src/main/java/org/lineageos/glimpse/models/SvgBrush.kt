/* Bounded, local-only SVG path brush parser and vector stamp renderer.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.models

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Xml
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

/** A deliberately small SVG subset: one viewBox and up to 64 filled path elements.
 * Unsupported features are rejected, not silently loaded or executed.
 */
class SvgBrush private constructor(private val bounds: RectF, private val paths: List<Pair<Path, Int>>) {
    val isColored: Boolean = paths.any { (_, c) ->
        max(abs(Color.red(c) - Color.green(c)), max(abs(Color.red(c) - Color.blue(c)),
            abs(Color.green(c) - Color.blue(c)))) > 25
    }

    fun draw(canvas: Canvas, size: Float, color: Int, opacity: Int, mode: BrushColorMode) {
        val scale = size / max(bounds.width(), bounds.height())
        if (!scale.isFinite() || scale <= 0f) return
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-bounds.centerX(), -bounds.centerY())
        canvas.clipRect(bounds)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for ((path, original) in paths) {
            val chosen = if (mode == BrushColorMode.TINT) color else original
            paint.color = chosen
            val tintAlpha = if (mode == BrushColorMode.TINT) Color.alpha(chosen) else 255
            paint.alpha = Color.alpha(original) * tintAlpha / 255 * opacity.coerceIn(0, 100) / 100
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        private const val MAX_PATHS = 64
        private const val MAX_TOKENS = 4000
        private val number = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        private val token = Regex("[MmLlHhVvCcSsQqTtZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

        fun readBounded(input: InputStream): ByteArray? {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0 || output.size() + read > MAX_BYTES) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        fun fromFile(file: File): SvgBrush? = runCatching {
            if (!file.isFile || file.length() > MAX_BYTES) return null
            file.inputStream().use { input -> parse(readBounded(input) ?: return null) }
        }.getOrNull()

        @android.annotation.SuppressLint("RestrictedApi") // AndroidX path parser; input is bounded and validated.
        fun parse(bytes: ByteArray): SvgBrush? = runCatching {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var rootSeen = false
            var rootClosed = false
            var bounds: RectF? = null
            val paths = mutableListOf<Pair<Path, Int>>()
            var tokens = 0
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF, XmlPullParser.PROCESSING_INSTRUCTION -> error("Unsupported XML")
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> require(parser.text.isBlank())
                    XmlPullParser.START_TAG -> {
                        require(!rootClosed)
                        require(parser.namespace.isNullOrEmpty() || parser.namespace == "http://www.w3.org/2000/svg")
                        val attributes = (0 until parser.attributeCount).associate { index ->
                            require(parser.getAttributeNamespace(index).isNullOrEmpty())
                            parser.getAttributeName(index) to parser.getAttributeValue(index)
                        }
                        if (parser.depth == 1 && parser.name == "svg" && !rootSeen) {
                            rootSeen = true
                            require(attributes.keys.all { it in setOf("viewBox", "width", "height", "version") })
                            val values = requireNotNull(attributes["viewBox"]).trim().split(Regex("[\\s,]+"))
                            require(values.size == 4)
                            val nums = values.map { value ->
                                require(number.matches(value)); value.toFloat().also { require(it.isFinite() && abs(it) <= 100000f) }
                            }
                            require(nums[2] in 0.01f..100000f && nums[3] in 0.01f..100000f)
                            bounds = RectF(nums[0], nums[1], nums[0] + nums[2], nums[1] + nums[3])
                        } else {
                            require(rootSeen && parser.depth == 2 && parser.name == "path" && paths.size < MAX_PATHS)
                            require(attributes.keys.all { it in setOf("d", "fill", "fill-opacity", "fill-rule") })
                            val d = requireNotNull(attributes["d"])
                            require(d.length in 1..65536)
                            var end = 0
                            for (match in token.findAll(d)) {
                                require(d.substring(end, match.range.first).all { it.isWhitespace() || it == ',' })
                                val t = match.value
                                if (number.matches(t)) require(t.toFloat().let { it.isFinite() && abs(it) <= 100000f })
                                tokens++
                                require(tokens <= MAX_TOKENS)
                                end = match.range.last + 1
                            }
                            require(d.substring(end).all { it.isWhitespace() || it == ',' })
                            val path = requireNotNull(PathParser.createPathFromPathData(d))
                            path.fillType = when (attributes["fill-rule"] ?: "nonzero") {
                                "nonzero" -> Path.FillType.WINDING
                                "evenodd" -> Path.FillType.EVEN_ODD
                                else -> error("Unsupported fill rule")
                            }
                            val fill = attributes["fill"] ?: "black"
                            require(fill != "none")
                            val color = Color.parseColor(fill)
                            val alpha = attributes["fill-opacity"]?.toFloat() ?: 1f
                            require(alpha.isFinite() && alpha in 0f..1f)
                            paths.add(path to ((color and 0x00ffffff) or ((Color.alpha(color) * alpha).toInt() shl 24)))
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.depth == 1) rootClosed = true
                }
            }
            require(rootSeen && rootClosed && paths.isNotEmpty())
            SvgBrush(requireNotNull(bounds), paths)
        }.getOrNull()
    }
}
