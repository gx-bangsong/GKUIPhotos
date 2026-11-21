/* Offline, private storage and bounded validation for imported markup fonts.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.models

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object MarkupFonts {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val faces = ConcurrentHashMap<String, Typeface>()
    private lateinit var directory: File

    fun initialize(context: Context) {
        directory = File(context.filesDir, "markup-fonts").apply { mkdirs() }
    }

    fun ids(): List<String> = if (!::directory.isInitialized) emptyList() else
        directory.listFiles()?.mapNotNull { file ->
            file.name.takeIf { it.matches(Regex("[0-9a-f]{64}\\.font")) }?.removeSuffix(".font")
        }?.sorted() ?: emptyList()

    fun resolve(id: String): Typeface = when (id) {
        "sans" -> Typeface.DEFAULT_BOLD
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        else -> faces[id] ?: if (::directory.isInitialized && id.matches(Regex("[0-9a-f]{64}"))) {
            runCatching { Typeface.createFromFile(File(directory, "$id.font")) }.getOrNull()
                ?.also { faces[id] = it } ?: Typeface.DEFAULT_BOLD
        } else Typeface.DEFAULT_BOLD
    }

    // Inspect cmap instead of Paint.hasGlyph: Android may otherwise report a
    // system-fallback glyph as if it existed in the imported face.
    private fun basicLatinPresent(bytes: ByteArray, table: Pair<Int, Int>): Boolean {
        val start = table.first
        val end = start + table.second
        fun u16(i: Int): Int = if (i >= start && i + 2 <= end)
            ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255) else -1
        fun u32(i: Int): Long = if (i >= start && i + 4 <= end)
            ((u16(i).toLong() shl 16) or u16(i + 2).toLong()) else -1
        val count = u16(start + 2)
        if (count !in 1..64 || start + 4L + count * 8L > end) return false
        val needed = intArrayOf(48, 65, 97)
        val found = BooleanArray(needed.size)
        repeat(count) { index ->
            val record = start + 4 + index * 8
            val platform = u16(record); val encoding = u16(record + 2)
            val offset = u32(record + 4)
            if (platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) return@repeat
            if (offset < 0 || offset > table.second - 2L) return@repeat
            val sub = start + offset.toInt()
            when (u16(sub)) {
                4 -> {
                    val length = u16(sub + 2); val segments = u16(sub + 6) / 2
                    if (length < 16 || sub + length.toLong() > end || segments !in 1..8192 ||
                        16L + segments * 8L > length) return@repeat
                    val limit = sub + length
                    val ends = sub + 14; val starts = ends + segments * 2 + 2
                    val deltas = starts + segments * 2; val ranges = deltas + segments * 2
                    needed.forEachIndexed { n, cp ->
                        for (i in 0 until segments) {
                            if (cp > u16(ends + i * 2)) continue
                            val from = u16(starts + i * 2)
                            if (cp < from) break
                            val delta = u16(deltas + i * 2)
                            val range = u16(ranges + i * 2)
                            val glyph = if (range == 0) (cp + delta) and 65535 else {
                                val at = ranges + i * 2L + range + 2L * (cp - from)
                                if (at < sub || at + 2 > limit) 0 else u16(at.toInt()).let {
                                    if (it <= 0) 0 else (it + delta) and 65535
                                }
                            }
                            if (glyph != 0) found[n] = true
                            break
                        }
                    }
                }
                12 -> {
                    val length = u32(sub + 4); val groups = u32(sub + 12)
                    if (length < 16 || sub.toLong() + length > end || groups !in 0L..65536L ||
                        16L + 12L * groups > length) return@repeat
                    repeat(groups.toInt()) { i ->
                        val at = sub + 16 + i * 12
                        val first = u32(at); val last = u32(at + 4); val firstGlyph = u32(at + 8)
                        needed.forEachIndexed { n, cp ->
                            if (cp.toLong() in first..last && firstGlyph + cp - first > 0) found[n] = true
                        }
                    }
                }
            }
        }
        return found.all { it }
    }

    /** Accept sfnt TrueType or CFF OpenType, never collections or WOFF. */
    private fun validSfnt(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val signature = b.int
        if (signature != 0x00010000 && signature != 0x4f54544f) return false
        val count = b.short.toInt() and 0xffff
        if (count !in 1..128 || 12L + 16L * count > bytes.size) return false
        val tables = mutableMapOf<Int, Pair<Int, Int>>()
        repeat(count) { i ->
            b.position(12 + i * 16)
            val tag = b.int
            b.int // checksum
            val offset = b.int.toLong() and 0xffffffffL
            val length = b.int.toLong() and 0xffffffffL
            if (offset > bytes.size || length > bytes.size || offset + length > bytes.size ||
                tables.containsKey(tag)) return false
            tables[tag] = offset.toInt() to length.toInt()
        }
        val maxp = tables[0x6d617870] ?: return false
        if (maxp.second < 6 || !tables.containsKey(0x636d6170) || !tables.containsKey(0x68656164)) return false
        val glyphs = ByteBuffer.wrap(bytes, maxp.first + 4, 2).short.toInt() and 0xffff
        if (glyphs !in 1..32768 || !basicLatinPresent(bytes, tables.getValue(0x636d6170))) return false
        return if (signature == 0x4f54544f) tables.containsKey(0x43464620) || tables.containsKey(0x43464632)
            else tables.containsKey(0x676c7966) && tables.containsKey(0x6c6f6361)
    }

    /** Reads at most MAX_BYTES + 1, validates the actual bytes and asks Android to load it. */
    fun importFont(context: Context, uri: Uri): String? = runCatching {
        if (!::directory.isInitialized) initialize(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: return null
        if (!validSfnt(bytes)) return null
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val finalFile = File(directory, "$id.font")
        val temp = File.createTempFile("font-", ".tmp", directory)
        try {
            temp.writeBytes(bytes)
            val face = Typeface.createFromFile(temp)
            val paint = Paint().apply { typeface = face }
            if (!paint.hasGlyph("A") || !paint.hasGlyph("a") || !paint.hasGlyph("0")) return null
            if (!finalFile.exists() && !temp.renameTo(finalFile)) return null
            faces[id] = face
            id
        } finally {
            temp.delete()
        }
    }.getOrNull()
}
