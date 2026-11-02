/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class CustomShape(
    val id: String,
    val name: String,
    val pathData: String,
) {
    companion object {
        fun serializeList(shapes: List<CustomShape>): String {
            return shapes.joinToString("\n") { s ->
                "${s.id}\t${s.name}\t${s.pathData}"
            }
        }

        fun deserializeList(content: String?): List<CustomShape> {
            if (content.isNullOrBlank()) return emptyList()
            val list = mutableListOf<CustomShape>()
            for (line in content.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 3) {
                    val id = parts[0]
                    val name = parts[1]
                    val pathData = parts[2]
                    list.add(CustomShape(id, name, pathData))
                }
            }
            return list
        }
    }
}

data class PathBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
}

sealed class CustomShapeParseResult {
    data class Success(
        val name: String,
        val normalizedPathData: String,
        val commandCount: Int,
        val bounds: PathBounds,
    ) : CustomShapeParseResult()

    data class Error(val message: String) : CustomShapeParseResult()
}

/**
 * Pure Kotlin parser and normalizer for SVG path data and polygon point lists.
 * Enforces file size limit (256 KB), command limits (2000), aspect-ratio preserving unit normalization,
 * and strict error handling without Android framework dependencies.
 */
object CustomShapeParser {

    private const val MAX_FILE_SIZE_BYTES = 256 * 1024
    private const val MAX_COMMANDS = 2000

    private val SUPPORTED_COMMANDS = setOf(
        'M', 'm', 'L', 'l', 'H', 'h', 'V', 'v', 'C', 'c', 'Q', 'q', 'Z', 'z'
    )

    fun parse(content: String, defaultName: String = "自定义形状"): CustomShapeParseResult {
        if (content.isBlank()) {
            return CustomShapeParseResult.Error("输入内容为空")
        }

        if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_SIZE_BYTES) {
            return CustomShapeParseResult.Error("文件超过大小限制 (256 KB)")
        }

        val trimmed = content.trim()
        var resolvedName = defaultName

        // 1. Check if SVG document
        if (trimmed.contains("<svg", ignoreCase = true) || trimmed.contains("<path", ignoreCase = true)) {
            val titleRegex = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            titleRegex.find(trimmed)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotBlank()) resolvedName = it
            }
            val dRegex = Regex("""\bd\s*=\s*["']([^"']+)["']""")
            val pathMatches = dRegex.findAll(trimmed).map { it.groupValues[1] }.toList()
            if (pathMatches.isEmpty()) {
                return CustomShapeParseResult.Error("未在 SVG 中找到有效的路径数据 (d 属性)")
            }
            val combinedPath = pathMatches.joinToString(" ")
            return parseSvgPathData(combinedPath, resolvedName)
        }

        // 2. Check if JSON format
        if (trimmed.startsWith("{")) {
            val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
            nameRegex.find(trimmed)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotBlank()) resolvedName = it
            }
            val pathRegex = Regex(""""path"\s*:\s*"([^"]+)"""")
            val pathVal = pathRegex.find(trimmed)?.groupValues?.get(1)
            if (!pathVal.isNullOrBlank()) {
                return parseSvgPathData(pathVal, resolvedName)
            }
            val coordsRegex = Regex(""""(?:coordinates|points)"\s*:\s*(\[[^}]+\])""")
            val coordsVal = coordsRegex.find(trimmed)?.groupValues?.get(1)
            if (!coordsVal.isNullOrBlank()) {
                return parsePolygon(coordsVal, resolvedName)
            }
        }

        // 3. Check if polygon point list (plain text)
        if (isPolygonInput(trimmed)) {
            return parsePolygon(trimmed, defaultName)
        }

        // 4. Otherwise treat as raw SVG path data string
        return parseSvgPathData(trimmed, defaultName)
    }

    private fun isPolygonInput(str: String): Boolean {
        if (str.startsWith("[")) return true
        // If it starts with coordinates (e.g. "0,0 1,0 0.5,1" or "0 0, 100 0")
        // and doesn't start with standard SVG command like 'M' or 'm'
        val firstChar = str.firstOrNull { !it.isWhitespace() } ?: return false
        return firstChar == '[' || firstChar.isDigit() || firstChar == '-' || firstChar == '+' || firstChar == '('
    }

    private fun parsePolygon(content: String, name: String): CustomShapeParseResult {
        val numberRegex = Regex("""[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?""")
        val numbers = numberRegex.findAll(content).mapNotNull { it.value.toFloatOrNull() }.toList()

        if (numbers.size < 6 || numbers.size % 2 != 0) {
            return CustomShapeParseResult.Error("多边形顶点数据无效 (顶点数不足 3 个或坐标不成对)")
        }

        val sb = StringBuilder()
        sb.append("M ${numbers[0]} ${numbers[1]} ")
        for (i in 2 until numbers.size step 2) {
            sb.append("L ${numbers[i]} ${numbers[i + 1]} ")
        }
        sb.append("Z")

        return parseSvgPathData(sb.toString(), name)
    }

    private sealed class RawCmd {
        data class MoveTo(val x: Float, val y: Float) : RawCmd()
        data class LineTo(val x: Float, val y: Float) : RawCmd()
        data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : RawCmd()
        data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : RawCmd()
        object Close : RawCmd()
    }

    private fun parseSvgPathData(pathData: String, name: String): CustomShapeParseResult {
        val tokenRegex = Regex("""([a-zA-Z])|([-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?)""")
        val tokens = tokenRegex.findAll(pathData).map { it.value }.toList()

        if (tokens.isEmpty()) {
            return CustomShapeParseResult.Error("无效的路径数据")
        }

        var tokenIndex = 0
        var currentCmd = ' '
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        fun updateBounds(x: Float, y: Float) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val commands = mutableListOf<RawCmd>()
        var commandCount = 0

        fun nextFloat(): Float? {
            if (tokenIndex >= tokens.size) return null
            val tok = tokens[tokenIndex]
            if (tok.length == 1 && tok[0].isLetter()) return null
            tokenIndex++
            return tok.toFloatOrNull()
        }

        while (tokenIndex < tokens.size) {
            val token = tokens[tokenIndex]
            if (token.length == 1 && token[0].isLetter()) {
                currentCmd = token[0]
                tokenIndex++
            } else if (currentCmd == ' ') {
                return CustomShapeParseResult.Error("路径数据未以有效指令开头: '$token'")
            }

            if (currentCmd == 'A' || currentCmd == 'a') {
                return CustomShapeParseResult.Error("暂不支持椭圆弧 (A/a) 指令")
            }

            if (currentCmd !in SUPPORTED_COMMANDS) {
                return CustomShapeParseResult.Error("不支持的路径指令: '$currentCmd' (仅支持 M, L, H, V, C, Q, Z)")
            }

            commandCount++
            if (commandCount > MAX_COMMANDS) {
                return CustomShapeParseResult.Error("路径指令过多 (超过 2000 条)")
            }

            when (currentCmd) {
                'M', 'm' -> {
                    val isRel = currentCmd == 'm'
                    val x = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少坐标参数")
                    val y = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少坐标参数")
                    curX = if (isRel) curX + x else x
                    curY = if (isRel) curY + y else y
                    startX = curX
                    startY = curY
                    updateBounds(curX, curY)
                    commands.add(RawCmd.MoveTo(curX, curY))
                    // Subsequent coordinates treat as LineTo
                    currentCmd = if (isRel) 'l' else 'L'
                }
                'L', 'l' -> {
                    val isRel = currentCmd == 'l'
                    val x = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少坐标参数")
                    val y = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少坐标参数")
                    curX = if (isRel) curX + x else x
                    curY = if (isRel) curY + y else y
                    updateBounds(curX, curY)
                    commands.add(RawCmd.LineTo(curX, curY))
                }
                'H', 'h' -> {
                    val isRel = currentCmd == 'h'
                    val x = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    curX = if (isRel) curX + x else x
                    updateBounds(curX, curY)
                    commands.add(RawCmd.LineTo(curX, curY))
                }
                'V', 'v' -> {
                    val isRel = currentCmd == 'v'
                    val y = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    curY = if (isRel) curY + y else y
                    updateBounds(curX, curY)
                    commands.add(RawCmd.LineTo(curX, curY))
                }
                'C', 'c' -> {
                    val isRel = currentCmd == 'c'
                    val x1 = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val y1 = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val x2 = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val y2 = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val x = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val y = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")

                    val cx1 = if (isRel) curX + x1 else x1
                    val cy1 = if (isRel) curY + y1 else y1
                    val cx2 = if (isRel) curX + x2 else x2
                    val cy2 = if (isRel) curY + y2 else y2
                    curX = if (isRel) curX + x else x
                    curY = if (isRel) curY + y else y

                    updateBounds(cx1, cy1)
                    updateBounds(cx2, cy2)
                    updateBounds(curX, curY)
                    commands.add(RawCmd.CubicTo(cx1, cy1, cx2, cy2, curX, curY))
                }
                'Q', 'q' -> {
                    val isRel = currentCmd == 'q'
                    val x1 = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val y1 = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val x = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")
                    val y = nextFloat() ?: return CustomShapeParseResult.Error("指令 $currentCmd 缺少参数")

                    val cx1 = if (isRel) curX + x1 else x1
                    val cy1 = if (isRel) curY + y1 else y1
                    curX = if (isRel) curX + x else x
                    curY = if (isRel) curY + y else y

                    updateBounds(cx1, cy1)
                    updateBounds(curX, curY)
                    commands.add(RawCmd.QuadTo(cx1, cy1, curX, curY))
                }
                'Z', 'z' -> {
                    curX = startX
                    curY = startY
                    commands.add(RawCmd.Close)
                    currentCmd = ' '
                }
            }
        }

        if (commands.isEmpty() || minX.isInfinite() || maxX.isInfinite()) {
            return CustomShapeParseResult.Error("未解析到有效的路径指令")
        }

        val width = maxX - minX
        val height = maxY - minY
        if (width <= 1e-5f || height <= 1e-5f) {
            return CustomShapeParseResult.Error("退化图形：宽度或高度为 0")
        }

        // Normalize into unit square [0, 1] preserving aspect ratio, centered
        val maxDim = max(width, height)
        val scale = 1.0f / maxDim
        val offsetX = (1.0f - width * scale) / 2.0f - minX * scale
        val offsetY = (1.0f - height * scale) / 2.0f - minY * scale

        fun normX(x: Float): String = String.format(Locale.US, "%.5f", x * scale + offsetX)
        fun normY(y: Float): String = String.format(Locale.US, "%.5f", y * scale + offsetY)

        val normalizedSb = StringBuilder()
        for (cmd in commands) {
            when (cmd) {
                is RawCmd.MoveTo -> normalizedSb.append("M ${normX(cmd.x)} ${normY(cmd.y)} ")
                is RawCmd.LineTo -> normalizedSb.append("L ${normX(cmd.x)} ${normY(cmd.y)} ")
                is RawCmd.QuadTo -> normalizedSb.append("Q ${normX(cmd.x1)} ${normY(cmd.y1)} ${normX(cmd.x)} ${normY(cmd.y)} ")
                is RawCmd.CubicTo -> normalizedSb.append("C ${normX(cmd.x1)} ${normY(cmd.y1)} ${normX(cmd.x2)} ${normY(cmd.y2)} ${normX(cmd.x)} ${normY(cmd.y)} ")
                is RawCmd.Close -> normalizedSb.append("Z ")
            }
        }

        val bounds = PathBounds(minX, minY, maxX, maxY)
        return CustomShapeParseResult.Success(
            name = name,
            normalizedPathData = normalizedSb.toString().trim(),
            commandCount = commandCount,
            bounds = bounds,
        )
    }
}
