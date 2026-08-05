/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A fully offline, dependency-free GIF decoder + encoder implemented with the
 * standard GIF89a LZW format. No external/native library is required, which keeps
 * the whole feature matrix strictly on-device.
 *
 * Decoding produces an ordered list of [GifFrame]s (each an [Bitmap] and its
 * delay in milliseconds) with correct frame compositing/disposal. Encoding takes
 * an ordered list of [GifFrame]s and writes an animated GIF89a stream, performing
 * on-device color quantization (median cut) when the combined palette exceeds
 * 256 colors.
 */
object GifCodec {

    /** A single decoded/encoded GIF frame. */
    data class GifFrame(
        val bitmap: Bitmap,
        /** Frame delay in milliseconds. */
        val delayMs: Int,
    )

    /**
     * Maximum number of pixels sampled (across all frames) to build the global
     * palette. Bounded so multi-frame video GIFs don't blow up memory/time.
     */
    private const val PALETTE_SAMPLE_SIZE = 20000

    // ----------------------------------------------------------------------
    // Decoder
    // ----------------------------------------------------------------------

    /**
     * Decode a GIF stream into its individual frames.
     *
     * @param inputStream The GIF byte stream (not closed by this method).
     * @param maxFrames Safety cap to avoid runaway memory use on malformed input.
     */
    fun decode(inputStream: InputStream, maxFrames: Int = 512): List<GifFrame> {
        val src = inputStream.readBytes()
        if (src.size < 13 ||
            src[0] != 'G'.code.toByte() ||
            src[1] != 'I'.code.toByte() ||
            src[2] != 'F'.code.toByte()
        ) {
            throw IllegalArgumentException("Not a GIF stream")
        }

        var p = 6
        // Logical Screen Descriptor
        val width = u16(src, p).also { p += 2 }
        val height = u16(src, p).also { p += 2 }
        val packed = src[p].toInt() and 0xFF
        val gctFlag = (packed and 0x80) != 0
        val gctSize = 2 shl (packed and 0x07)
        p += 2 // background color index + pixel aspect ratio

        val gct: IntArray = if (gctFlag) {
            val table = readColorTable(src, p, gctSize)
            p += 3 * gctSize
            table
        } else IntArray(0)

        val frames = ArrayList<GifFrame>()
        // Persistent compositing canvas.
        var canvas: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        var delayMs = 100
        var transparentIndex = -1
        var disposal = 0

        while (p < src.size) {
            when (val blockId = src[p].toInt() and 0xFF) {
                0x3B -> break // Trailer
                0x21 -> { // Extension
                    val label = src[++p].toInt() and 0xFF
                    p++
                    if (label == 0xF9) { // Graphic Control Extension
                        val blockSize = src[p].toInt() and 0xFF
                        val gcePacked = src[p + 1].toInt() and 0xFF
                        delayMs = u16(src, p + 2) * 10
                        if (delayMs == 0) delayMs = 100
                        transparentIndex = if ((gcePacked and 0x01) != 0) {
                            src[p + 4].toInt() and 0xFF
                        } else -1
                        disposal = (gcePacked shr 2) and 0x07
                        p += blockSize + 2 // block + terminator
                    } else {
                        p = skipSubBlocks(src, p)
                    }
                }
                0x2C -> { // Image Descriptor
                    p++
                    val left = u16(src, p)
                    val top = u16(src, p + 2)
                    val fWidth = u16(src, p + 4)
                    val fHeight = u16(src, p + 6)
                    val fPacked = src[p + 8].toInt() and 0xFF
                    val lctFlag = (fPacked and 0x80) != 0
                    val interlace = (fPacked and 0x40) != 0
                    val lctSize = 2 shl (fPacked and 0x07)
                    p += 9

                    val act = if (lctFlag) {
                        val table = readColorTable(src, p, lctSize)
                        p += 3 * lctSize
                        table
                    } else {
                        gct
                    }

                    p = drawFrame(
                        src, p, fWidth, fHeight, interlace, act, transparentIndex,
                        left, top, canvas, disposal, frames, delayMs,
                    )

                    delayMs = 100
                    transparentIndex = -1
                    disposal = 0

                    if (frames.size >= maxFrames) break
                }
                else -> p++ // Unknown block; advance to avoid an infinite loop.
            }
        }

        return frames
    }

    /**
     * Decompress one frame's LZW data, draw it onto the persistent [canvas] at
     * (left, top) honoring the transparent index, append a compositing snapshot
     * to [frames], then apply the disposal method so the next frame composites
     * correctly. Returns the new read position.
     */
    private fun drawFrame(
        src: ByteArray, p0: Int, width: Int, height: Int, interlace: Boolean,
        act: IntArray, transparentIndex: Int,
        left: Int, top: Int,
        canvas: Bitmap, disposal: Int,
        frames: ArrayList<GifFrame>, delayMs: Int,
    ): Int {
        var p = p0
        val minCodeSize = src[p].toInt() and 0xFF
        p++

        val compressed = ByteArrayOutputStream()
        while (true) {
            val size = src[p].toInt() and 0xFF
            p++
            if (size == 0) break
            compressed.write(src, p, size)
            p += size
        }
        val pixels = lzwDecode(compressed.toByteArray(), minCodeSize, width * height)

        // Save a snapshot for "restore to previous" disposal.
        val saved = if (disposal == 3) canvas.copy(Bitmap.Config.ARGB_8888, true) else null

        // If the frame is full-size with dispose=background, clear first.
        if (disposal == 2) {
            // Best-effort: clear the region we're about to overwrite.
        }

        // Build the frame pixels and draw them onto the canvas.
        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val framePixels = IntArray(width * height)
        for (i in pixels.indices) {
            val index = pixels[i].toInt() and 0xFF
            framePixels[i] = if (index == transparentIndex) {
                Color.TRANSPARENT
            } else if (index < act.size) {
                act[index] or 0xFF000000.toInt()
            } else {
                Color.BLACK
            }
        }
        frameBitmap.setPixels(framePixels, 0, width, 0, 0, width, height)

        val c = Canvas(canvas)
        c.drawBitmap(frameBitmap, left.toFloat(), top.toFloat(), null)
        frameBitmap.recycle()

        // Emit a snapshot of the composited canvas as the frame.
        val snapshot = canvas.copy(Bitmap.Config.ARGB_8888, true)
        frames.add(GifFrame(snapshot, delayMs))

        // Apply disposal for the NEXT frame.
        when (disposal) {
            2 -> {
                // Restore to background: clear the area this frame occupied.
                val paint = android.graphics.Paint().apply { color = Color.TRANSPARENT }
                c.drawRect(Rect(left, top, left + width, top + height), paint)
            }
            3 -> {
                // Restore to previous: copy back the saved canvas.
                saved?.let {
                    c.drawBitmap(it, 0f, 0f, null)
                    it.recycle()
                }
            }
        }

        return p
    }

    private fun readColorTable(src: ByteArray, p: Int, size: Int): IntArray {
        val table = IntArray(size)
        var q = p
        for (i in 0 until size) {
            table[i] = (
                0xFF000000.toInt() or
                    ((src[q].toInt() and 0xFF) shl 16) or
                    ((src[q + 1].toInt() and 0xFF) shl 8) or
                    (src[q + 2].toInt() and 0xFF)
                )
            q += 3
        }
        return table
    }

    private fun skipSubBlocks(src: ByteArray, p0: Int): Int {
        var p = p0
        while (true) {
            val size = src[p].toInt() and 0xFF
            p++
            if (size == 0) return p
            p += size
        }
    }

    private fun lzwDecode(data: ByteArray, minCodeSize: Int, pixelCount: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var dict = Array<ByteArray?>(clearCode + 2) { byteArrayOf(it.toByte()) }

        val out = ByteArrayOutputStream()
        var bitBuffer = 0
        var bitsInBuffer = 0
        var dataPos = 0
        var prev: ByteArray? = null

        while (true) {
            while (bitsInBuffer < codeSize && dataPos < data.size) {
                bitBuffer = bitBuffer or ((data[dataPos].toInt() and 0xFF) shl bitsInBuffer)
                bitsInBuffer += 8
                dataPos++
            }
            if (bitsInBuffer < codeSize) break
            val code = bitBuffer and ((1 shl codeSize) - 1)
            bitBuffer = bitBuffer shr codeSize
            bitsInBuffer -= codeSize

            if (code == clearCode) {
                codeSize = minCodeSize + 1
                dict = Array(clearCode + 2) { byteArrayOf(it.toByte()) }
                prev = null
                continue
            }
            if (code == endCode) break

            val entry: ByteArray = when {
                code < dict.size && dict[code] != null -> dict[code]!!
                prev != null && code == dict.size -> prev + byteArrayOf(prev[0])
                else -> break
            }
            out.write(entry)
            if (prev != null && dict.size < 4096) {
                val grown = dict.copyOf(dict.size + 1)
                grown[grown.size - 1] = prev + byteArrayOf(entry[0])
                dict = grown
                if (dict.size == (1 shl codeSize) && codeSize < 12) {
                    codeSize++
                }
            }
            prev = entry
        }

        val bytes = out.toByteArray()
        return if (bytes.size >= pixelCount) bytes.copyOf(pixelCount) else bytes
    }

    private fun u16(src: ByteArray, p: Int) =
        (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)

    // ----------------------------------------------------------------------
    // Encoder
    // ----------------------------------------------------------------------

    /**
     * Encode [frames] as an animated GIF89a into [out]. All frames must share the
     * same dimensions; they are resized/center-cropped to the first frame's size.
     *
     * @param loopCount 0 = loop forever.
     */
    fun encode(frames: List<GifFrame>, out: OutputStream, loopCount: Int = 0) {
        require(frames.isNotEmpty()) { "No frames to encode" }
        val base = frames.first().bitmap
        val width = base.width
        val height = base.height

        // Normalize all frames to ARGB_8888 at the target size.
        val normalized = frames.map { frame ->
            val bmp = if (frame.bitmap.config != Bitmap.Config.ARGB_8888 ||
                frame.bitmap.width != width || frame.bitmap.height != height
            ) {
                val tmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val c = Canvas(tmp)
                val src = frame.bitmap
                val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
                val w = src.width * scale
                val h = src.height * scale
                val dest = Rect(
                    ((width - w) / 2f).toInt(),
                    ((height - h) / 2f).toInt(),
                    ((width + w) / 2f).toInt(),
                    ((height + h) / 2f).toInt()
                )
                c.drawBitmap(src, null, dest, null)
                tmp
            } else {
                frame.bitmap
            }
            val px = IntArray(width * height)
            bmp.getPixels(px, 0, width, 0, 0, width, height)
            IndexedFrame(px, frame.delayMs)
        }

        // Build the global palette from a BOUNDED sample of pixels. Materializing
        // every pixel of every frame (flatMap + LinkedHashSet) explodes memory and
        // time for multi-frame video GIFs and is a guaranteed OOM on phones.
        val sample = ArrayList<Int>(PALETTE_SAMPLE_SIZE)
        val perFrame = (PALETTE_SAMPLE_SIZE / normalized.size).coerceAtLeast(1)
        for (frame in normalized) {
            val px = frame.pixels
            val stride = (px.size / perFrame).coerceAtLeast(1)
            var j = 0
            while (j < px.size && sample.size < PALETTE_SAMPLE_SIZE) {
                sample.add(px[j])
                j += stride
            }
            if (sample.size >= PALETTE_SAMPLE_SIZE) break
        }
        val palette = MedianCut.quantize(sample, 256)
        val indexMap = HashMap<Int, Int>(palette.colors.size)
        palette.colors.forEachIndexed { i, c -> indexMap[c] = i }

        // Per-pixel nearest-color cache (shared across frames). Video frames share
        // most of their colors, so this turns O(pixels*256) into ~O(unique colors).
        val pixelToIndex = HashMap<Int, Int>(indexMap.size * 2)
        pixelToIndex.putAll(indexMap)

        val header = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte())
        out.write(header)

        // Logical Screen Descriptor.
        val packed = 0x80 or 0x70 or (palette.bits - 1)
        out.write(width and 0xFF); out.write(width shr 8)
        out.write(height and 0xFF); out.write(height shr 8)
        out.write(packed)
        out.write(0)
        out.write(0)

        // Global Color Table.
        for (color in palette.colors) {
            out.write(Color.red(color))
            out.write(Color.green(color))
            out.write(Color.blue(color))
        }

        // NETSCAPE looping extension.
        out.write(0x21); out.write(0xFF)
        out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray())
        out.write(0x03); out.write(0x01)
        out.write(loopCount and 0xFF); out.write(loopCount shr 8)
        out.write(0x00)

        for (frame in normalized) {
            // Graphic Control Extension.
            out.write(0x21); out.write(0xF9)
            out.write(0x04)
            out.write(0x00)
            val d100 = frame.delayMs.coerceIn(20, 10000)
            out.write(d100 and 0xFF); out.write(d100 shr 8)
            out.write(0)
            out.write(0x00)

            // Image Descriptor.
            out.write(0x2C)
            out.write(0); out.write(0)
            out.write(0); out.write(0)
            out.write(width and 0xFF); out.write(width shr 8)
            out.write(height and 0xFF); out.write(height shr 8)
            out.write(0x00)

            val minCodeSize = maxOf(2, palette.bits)
            out.write(minCodeSize)
            val indexed = ByteArray(frame.pixels.size) { i ->
                val px = frame.pixels[i]
                pixelToIndex.getOrPut(px) {
                    indexMap[px] ?: nearestColor(palette.colors, px)
                }.toByte()
            }
            val compressed = lzwEncode(indexed, minCodeSize)
            writeSubBlocks(out, compressed)
            out.write(0x00)
        }

        out.write(0x3B)
        out.flush()
    }

    private fun writeSubBlocks(out: OutputStream, data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val chunk = minOf(255, data.size - i)
            out.write(chunk)
            out.write(data, i, chunk)
            i += chunk
        }
    }

    private fun nearestColor(palette: IntArray, rgb: Int): Int {
        val r = Color.red(rgb); val g = Color.green(rgb); val b = Color.blue(rgb)
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            val dr = Color.red(palette[i]) - r
            val dg = Color.green(palette[i]) - g
            val db = Color.blue(palette[i]) - b
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    private fun lzwEncode(indexed: ByteArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var dict = HashMap<List<Byte>, Int>(4096)
        // Only the literal color entries belong in the initial dictionary; the
        // clear/end codes must NOT be present (adding them collided with colors
        // 0/1 because e.g. 256.toByte() == 0.toByte()).
        for (i in 0 until clearCode) {
            dict[listOf(i.toByte())] = i
        }
        var nextCode = endCode + 1

        val out = ByteArrayOutputStream()
        var bitBuffer = 0
        var bitsInBuffer = 0

        fun writeCode(code: Int) {
            bitBuffer = bitBuffer or (code shl bitsInBuffer)
            bitsInBuffer += codeSize
            while (bitsInBuffer >= 8) {
                out.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer shr 8
                bitsInBuffer -= 8
            }
        }

        writeCode(clearCode)
        if (indexed.isEmpty()) {
            writeCode(endCode)
            if (bitsInBuffer > 0) out.write(bitBuffer and 0xFF)
            return out.toByteArray()
        }

        var index = 0
        var w = listOf(indexed[0])
        index++
        while (index < indexed.size) {
            val k = indexed[index]
            index++
            val wk = w + k
            if (dict.containsKey(wk)) {
                w = wk
            } else {
                writeCode(dict[w]!!)
                if (nextCode < 4096) {
                    dict[wk] = nextCode
                    nextCode++
                    if (nextCode == (1 shl codeSize) + 1 && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    writeCode(clearCode)
                    codeSize = minCodeSize + 1
                    dict = HashMap(4096)
                    for (i in 0 until clearCode) {
                        dict[listOf(i.toByte())] = i
                    }
                    nextCode = endCode + 1
                }
                w = listOf(k)
            }
        }
        writeCode(dict[w]!!)
        writeCode(endCode)
        if (bitsInBuffer > 0) out.write(bitBuffer and 0xFF)
        return out.toByteArray()
    }

    private data class IndexedFrame(val pixels: IntArray, val delayMs: Int)

    private data class Palette(val colors: IntArray, val bits: Int)

    /**
     * Median-cut color quantizer producing a palette of at most [maxColors] entries.
     * If the input already fits, returns the exact (deduplicated) colors padded to a
     * power of two (as required by the GIF global color table size field).
     */
    private object MedianCut {
        fun quantize(colors: List<Int>, maxColors: Int): Palette {
            val unique = LinkedHashSet(colors)
            return if (unique.size <= maxColors) {
                val arr = unique.toIntArray()
                val bits = maxOf(2, ceilLog2(arr.size))
                Palette(padToPowerOfTwo(arr, bits), bits)
            } else {
                val buckets = mutableListOf(unique.toMutableList())
                while (buckets.size < maxColors) {
                    var targetIdx = -1
                    var targetRange = -1
                    for (i in buckets.indices) {
                        if (buckets[i].size <= 1) continue
                        val r = findRange(buckets[i])
                        if (r > targetRange) {
                            targetRange = r
                            targetIdx = i
                        }
                    }
                    if (targetIdx == -1) break
                    val bucket = buckets.removeAt(targetIdx)
                    val channel = widestChannel(bucket)
                    @Suppress("UNCHECKED_CAST")
                    bucket.sortBy { (it shr (16 - 8 * channel)) and 0xFF }
                    val mid = bucket.size / 2
                    buckets.add(bucket.subList(0, mid).toMutableList())
                    buckets.add(bucket.subList(mid, bucket.size).toMutableList())
                }
                val avg = buckets.map { average(it) }.toIntArray()
                val bits = maxOf(2, ceilLog2(avg.size))
                Palette(padToPowerOfTwo(avg, bits), bits)
            }
        }

        private fun widestChannel(colors: List<Int>): Int {
            var rl = 255; var gl = 255; var bl = 255
            var rh = 0; var gh = 0; var bh = 0
            for (c in colors) {
                rl = minOf(rl, Color.red(c)); rh = maxOf(rh, Color.red(c))
                gl = minOf(gl, Color.green(c)); gh = maxOf(gh, Color.green(c))
                bl = minOf(bl, Color.blue(c)); bh = maxOf(bh, Color.blue(c))
            }
            val r = rh - rl; val g = gh - gl; val b = bh - bl
            return when (maxOf(r, g, b)) {
                r -> 0; g -> 1; else -> 2
            }
        }

        private fun findRange(colors: List<Int>): Int {
            var rl = 255; var gl = 255; var bl = 255
            var rh = 0; var gh = 0; var bh = 0
            for (c in colors) {
                rl = minOf(rl, Color.red(c)); rh = maxOf(rh, Color.red(c))
                gl = minOf(gl, Color.green(c)); gh = maxOf(gh, Color.green(c))
                bl = minOf(bl, Color.blue(c)); bh = maxOf(bh, Color.blue(c))
            }
            return maxOf(rh - rl, gh - gl, bh - bl)
        }

        private fun average(colors: List<Int>): Int {
            var r = 0; var g = 0; var b = 0
            for (c in colors) {
                r += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            val n = colors.size.coerceAtLeast(1)
            return Color.rgb(r / n, g / n, b / n)
        }

        private fun ceilLog2(n: Int) =
            (Integer.SIZE - Integer.numberOfLeadingZeros(n - 1)).coerceAtLeast(1)

        private fun padToPowerOfTwo(colors: IntArray, bits: Int): IntArray {
            val needed = 1 shl bits
            if (colors.size >= needed) return colors
            return colors.copyOf(needed)
        }
    }
}
