/* Independent GIF reader/writer and synthetic colour-block videos for GIF timing tests.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal GIF89a parser and writer. It deliberately shares no code with the
 * product GifCodec so encoder output can be checked independently.
 */
internal object MinimalGif {
    class Frame(
        /** Raw Graphic Control Extension delay, in 1/100 s. */
        val delayRaw: Int,
        val disposal: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        /** Composited logical screen after this frame, ARGB row-major. */
        val pixels: IntArray,
    )

    class Parsed(
        val version: String,
        val width: Int,
        val height: Int,
        val loopBlockPresent: Boolean,
        val loopCount: Int?,
        val frames: List<Frame>,
    ) {
        val delaysRaw: List<Int> get() = frames.map { it.delayRaw }
        val totalCs: Int get() = frames.sumOf { it.delayRaw }
    }

    fun parse(bytes: ByteArray): Parsed {
        require(bytes.size >= 13) { "GIF too short: ${bytes.size} bytes" }
        val version = String(bytes, 0, 6, Charsets.US_ASCII)
        require(version == "GIF87a" || version == "GIF89a") { "Bad GIF signature: $version" }
        fun u8(p: Int): Int {
            require(p in bytes.indices) { "Truncated GIF at offset $p" }
            return bytes[p].toInt() and 0xFF
        }
        fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)

        val width = u16(6)
        val height = u16(8)
        val screenPacked = u8(10)
        var p = 13
        var globalTable = IntArray(0)
        if ((screenPacked and 0x80) != 0) {
            val size = 2 shl (screenPacked and 0x07)
            globalTable = readTable(bytes, p, size)
            p += size * 3
        }

        val canvas = IntArray(width * height)
        val frames = ArrayList<Frame>()
        var loopBlockPresent = false
        var loopCount: Int? = null
        var delay = 0
        var disposal = 0
        var transparent = -1
        parsing@ while (true) {
            val block = u8(p)
            when (block) {
                0x3B -> break@parsing
                0x21 -> {
                    val label = u8(p + 1)
                    p += 2
                    when (label) {
                        0xF9 -> {
                            val size = u8(p)
                            require(size == 4) { "Bad Graphic Control Extension size $size" }
                            val packed = u8(p + 1)
                            delay = u16(p + 2)
                            transparent = if ((packed and 0x01) != 0) u8(p + 4) else -1
                            disposal = (packed shr 2) and 0x07
                            p += 1 + size
                            require(u8(p) == 0) { "Graphic Control Extension terminator missing at $p" }
                            p += 1
                        }
                        0xFF -> {
                            val size = u8(p)
                            require(p + 1 + size <= bytes.size) { "Truncated application extension" }
                            val application = String(bytes, p + 1, size, Charsets.US_ASCII)
                            p += 1 + size
                            val (blocks, next) = readSubBlocks(bytes, p)
                            if (application == "NETSCAPE2.0" || application == "ANIMEXTS1.0") {
                                loopBlockPresent = true
                                val first = blocks.firstOrNull()
                                if (first != null && first.size >= 3 && (first[0].toInt() and 0xFF) == 1) {
                                    loopCount = (first[1].toInt() and 0xFF) or ((first[2].toInt() and 0xFF) shl 8)
                                }
                            }
                            p = next
                        }
                        else -> p = readSubBlocks(bytes, p).second
                    }
                }
                0x2C -> {
                    val left = u16(p + 1)
                    val top = u16(p + 3)
                    val frameWidth = u16(p + 5)
                    val frameHeight = u16(p + 7)
                    val packed = u8(p + 9)
                    p += 10
                    var table = globalTable
                    if ((packed and 0x80) != 0) {
                        val size = 2 shl (packed and 0x07)
                        table = readTable(bytes, p, size)
                        p += size * 3
                    }
                    val interlaced = (packed and 0x40) != 0
                    val minCodeSize = u8(p)
                    p += 1
                    val (blocks, next) = readSubBlocks(bytes, p)
                    p = next
                    val data = ByteArrayOutputStream().apply { blocks.forEach { write(it) } }.toByteArray()
                    val indices = lzwDecode(data, minCodeSize, frameWidth * frameHeight)
                    val saved = if (disposal == 3) canvas.copyOf() else null
                    val rows = rowOrder(frameHeight, interlaced)
                    for (r in 0 until frameHeight) {
                        val y = top + rows[r]
                        if (y >= height) continue
                        for (x in 0 until frameWidth) {
                            val cx = left + x
                            if (cx >= width) continue
                            val index = indices[r * frameWidth + x]
                            if (index == transparent) continue
                            require(index < table.size) { "Colour index $index outside table of ${table.size}" }
                            canvas[y * width + cx] = table[index]
                        }
                    }
                    frames.add(Frame(delay, disposal, left, top, frameWidth, frameHeight, canvas.copyOf()))
                    if (disposal == 2) {
                        for (y in top until minOf(height, top + frameHeight)) {
                            for (x in left until minOf(width, left + frameWidth)) canvas[y * width + x] = 0
                        }
                    } else if (saved != null) {
                        saved.copyInto(canvas)
                    }
                    delay = 0
                    disposal = 0
                    transparent = -1
                }
                else -> throw IllegalArgumentException("Unexpected GIF block 0x${block.toString(16)} at offset $p")
            }
        }
        return Parsed(version, width, height, loopBlockPresent, loopCount, frames)
    }

    private fun readTable(bytes: ByteArray, p: Int, size: Int): IntArray {
        require(p + size * 3 <= bytes.size) { "Truncated colour table" }
        return IntArray(size) { i ->
            val q = p + i * 3
            (0xFF shl 24) or ((bytes[q].toInt() and 0xFF) shl 16) or
                ((bytes[q + 1].toInt() and 0xFF) shl 8) or (bytes[q + 2].toInt() and 0xFF)
        }
    }

    private fun readSubBlocks(bytes: ByteArray, start: Int): Pair<List<ByteArray>, Int> {
        val blocks = ArrayList<ByteArray>()
        var p = start
        while (true) {
            require(p in bytes.indices) { "Truncated sub-block chain at $p" }
            val size = bytes[p].toInt() and 0xFF
            p += 1
            if (size == 0) return blocks to p
            require(p + size <= bytes.size) { "Truncated sub-block at $p" }
            blocks.add(bytes.copyOfRange(p, p + size))
            p += size
        }
    }

    private fun rowOrder(height: Int, interlaced: Boolean): IntArray {
        if (!interlaced) return IntArray(height) { it }
        val order = ArrayList<Int>(height)
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < height) { order.add(y); y += step }
        }
        return order.toIntArray()
    }

    private fun lzwDecode(data: ByteArray, minCodeSize: Int, count: Int): IntArray {
        require(minCodeSize in 1..11) { "Bad LZW minimum code size $minCodeSize" }
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096)
        for (i in 0 until clear) suffix[i] = i
        val stack = IntArray(4097)
        val out = IntArray(count)
        var n = 0
        var codeSize = minCodeSize + 1
        var next = end + 1
        var old = -1
        var first = 0
        var buffer = 0
        var bits = 0
        var pos = 0
        while (n < count) {
            while (bits < codeSize && pos < data.size) {
                buffer = buffer or ((data[pos].toInt() and 0xFF) shl bits)
                bits += 8
                pos++
            }
            if (bits < codeSize) break
            var code = buffer and ((1 shl codeSize) - 1)
            buffer = buffer ushr codeSize
            bits -= codeSize
            if (code == clear) {
                codeSize = minCodeSize + 1
                next = end + 1
                old = -1
                continue
            }
            if (code == end) break
            if (old == -1) {
                require(code < clear) { "First LZW code $code after clear is not a literal" }
                out[n++] = code
                old = code
                first = code
                continue
            }
            val inCode = code
            var sp = 0
            if (code >= next) {
                require(code == next) { "LZW code $code beyond table size $next" }
                stack[sp++] = first
                code = old
            }
            while (code >= clear) {
                stack[sp++] = suffix[code]
                code = prefix[code]
            }
            first = code
            stack[sp++] = first
            while (sp > 0 && n < count) out[n++] = stack[--sp]
            if (next < 4096) {
                prefix[next] = old
                suffix[next] = first
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            old = inCode
        }
        require(n == count) { "LZW stream ended after $n of $count pixels" }
        return out
    }

    /**
     * Write a 4-colour GIF89a with exact raw delays. LZW data is emitted
     * uncompressed: a clear code every two pixels keeps every code 3 bits wide.
     */
    fun write(width: Int, height: Int, palette: IntArray, frames: List<Pair<ByteArray, Int>>, loopCount: Int = 0): ByteArray {
        require(palette.size == 4) { "Fixture writer supports exactly 4 colours" }
        val out = ByteArrayOutputStream()
        fun u16(value: Int) { out.write(value and 0xFF); out.write((value shr 8) and 0xFF) }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(width)
        u16(height)
        out.write(0x80 or (1 shl 4) or 1)
        out.write(0)
        out.write(0)
        palette.forEach { out.write((it shr 16) and 0xFF); out.write((it shr 8) and 0xFF); out.write(it and 0xFF) }
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1); u16(loopCount); out.write(0)
        frames.forEach { (indices, delayCs) ->
            require(indices.size == width * height) { "Frame size mismatch" }
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(1 shl 2)
            u16(delayCs)
            out.write(0); out.write(0)
            out.write(0x2C); u16(0); u16(0); u16(width); u16(height); out.write(0)
            out.write(2)
            val data = uncompressedLzw(indices)
            var i = 0
            while (i < data.size) {
                val chunk = minOf(255, data.size - i)
                out.write(chunk)
                out.write(data, i, chunk)
                i += chunk
            }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun uncompressedLzw(indices: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        fun put(code: Int) {
            buffer = buffer or (code shl bits)
            bits += 3
            while (bits >= 8) { out.write(buffer and 0xFF); buffer = buffer ushr 8; bits -= 8 }
        }
        indices.forEachIndexed { i, value ->
            if (i % 2 == 0) put(4)
            put(value.toInt() and 0x03)
        }
        put(5)
        if (bits > 0) out.write(buffer and 0xFF)
        return out.toByteArray()
    }
}

/** A generated video. Display size swaps coded width/height for 90° rotation metadata. */
internal data class VideoSpec(
    val name: String,
    val codedWidth: Int,
    val codedHeight: Int,
    val fps: Int,
    val frameCount: Int,
    val rotation: Int = 0,
    val mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val encoderName: String? = null,
) {
    val displayWidth: Int get() = if (rotation % 180 == 0) codedWidth else codedHeight
    val displayHeight: Int get() = if (rotation % 180 == 0) codedHeight else codedWidth
    val isWebm: Boolean get() = mime == MediaFormat.MIMETYPE_VIDEO_VP8 || mime == MediaFormat.MIMETYPE_VIDEO_VP9
}

internal data class EncoderProbe(
    val codec: String, val mime: String, val widthAlignment: Int, val heightAlignment: Int,
    val flexibleYuv: Boolean, val oddSizeSupported: Boolean,
)

internal data class VideoFacts(
    val mime: String, val width: Int, val height: Int, val rotation: Int,
    val samples: Int, val firstPtsUs: Long, val lastPtsUs: Long, val durationUs: Long,
)

/**
 * Colour-block frames: every frame has its own background colour, its index in
 * eleven black/white cells plus one always-white guard cell, and a red marker in
 * the displayed top-left corner.
 */
internal object SyntheticVideo {
    private const val COLUMNS = 6
    private const val ROWS = 2
    private const val INDEX_BITS = 11
    private const val RED = (0xFF shl 24) or (230 shl 16) or (30 shl 8) or 30
    private const val WHITE = (0xFF shl 24) or (235 shl 16) or (235 shl 8) or 235
    private const val BLACK = (0xFF shl 24) or (16 shl 16) or (16 shl 8) or 16

    private fun cellRect(k: Int, w: Int, h: Int): IntArray {
        val col = k % COLUMNS
        val row = k / COLUMNS
        val cellW = w * 0.8 / COLUMNS
        val cellH = h * 0.4 / ROWS
        return intArrayOf((w * 0.1 + col * cellW).toInt(), (h * 0.45 + row * cellH).toInt(),
            (w * 0.1 + (col + 1) * cellW).toInt(), (h * 0.45 + (row + 1) * cellH).toInt())
    }

    private fun fill(px: IntArray, w: Int, rect: IntArray, color: Int) {
        for (y in rect[1] until rect[3]) for (x in rect[0] until rect[2]) px[y * w + x] = color
    }

    fun displayFrame(index: Int, w: Int, h: Int): IntArray {
        val background = (0xFF shl 24) or (20 shl 16) or ((70 + (index * 37) % 120) shl 8) or (110 + (index * 53) % 120)
        val px = IntArray(w * h) { background }
        fill(px, w, intArrayOf(0, 0, w / 4, h / 4), RED)
        for (k in 0 until COLUMNS * ROWS) {
            val on = if (k < INDEX_BITS) ((index shr k) and 1) == 1 else true
            fill(px, w, cellRect(k, w, h), if (on) WHITE else BLACK)
        }
        return px
    }

    /** Frame as stored in the stream; a 90° hint means display = coded rotated clockwise. */
    fun codedFrame(spec: VideoSpec, index: Int): IntArray {
        val dw = spec.displayWidth
        val display = displayFrame(index, dw, spec.displayHeight)
        if (spec.rotation == 0) return display
        require(spec.rotation == 90) { "Only 0° and 90° fixtures are generated" }
        val cw = spec.codedWidth
        return IntArray(cw * spec.codedHeight) { i ->
            val cx = i % cw
            val cy = i / cw
            display[cx * dw + (dw - 1 - cy)]
        }
    }

    private fun mean(px: IntArray, w: Int, rect: IntArray, inset: Double): IntArray {
        val dx = ((rect[2] - rect[0]) * inset).toInt()
        val dy = ((rect[3] - rect[1]) * inset).toInt()
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in rect[1] + dy until rect[3] - dy) for (x in rect[0] + dx until rect[2] - dx) {
            val c = px[y * w + x]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        if (n == 0L) return intArrayOf(-1, -1, -1)
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Decode the frame index from a display-oriented frame, or null if guards fail. */
    fun decodeIndex(px: IntArray, w: Int, h: Int): Int? {
        var value = 0
        for (k in 0 until COLUMNS * ROWS) {
            val c = mean(px, w, cellRect(k, w, h), 0.25)
            val luma = (299 * c[0] + 587 * c[1] + 114 * c[2]) / 1000
            val bit = when {
                luma > 170 -> 1
                luma in 0..79 -> 0
                else -> return null
            }
            if (k < INDEX_BITS) value = value or (bit shl k) else if (bit != 1) return null
        }
        return value
    }

    /** Corners (TL, TR, BL, BR) whose centre patch is the red marker colour. */
    fun redCorners(px: IntArray, w: Int, h: Int): List<String> {
        val qw = w / 4
        val qh = h / 4
        val corners = linkedMapOf(
            "TL" to intArrayOf(0, 0, qw, qh), "TR" to intArrayOf(w - qw, 0, w, qh),
            "BL" to intArrayOf(0, h - qh, qw, h), "BR" to intArrayOf(w - qw, h - qh, w, h),
        )
        return corners.filter { (_, rect) ->
            val c = mean(px, w, rect, 0.25)
            c[0] > 150 && c[1] < 100 && c[2] < 100
        }.keys.toList()
    }

    fun probeEncoders(width: Int, height: Int): List<EncoderProbe> {
        val mimes = setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9)
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.flatMap { info ->
            info.supportedTypes.filter { it.lowercase() in mimes }.mapNotNull { type ->
                val caps = info.getCapabilitiesForType(type)
                val video = caps.videoCapabilities ?: return@mapNotNull null
                EncoderProbe(info.name, type.lowercase(), video.widthAlignment, video.heightAlignment,
                    caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible),
                    video.isSizeSupported(width, height))
            }
        }
    }

    /** Encode [spec] to [file]; returns the encoder name. Every loop is bounded by [deadline]. */
    fun encode(spec: VideoSpec, file: File, deadline: Long): String {
        val format = MediaFormat.createVideoFormat(spec.mime, spec.codedWidth, spec.codedHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (spec.codedWidth * spec.codedHeight * spec.fps / 4).coerceAtLeast(1_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val name = spec.encoderName
            ?: requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)) {
                "No encoder for $format"
            }
        val codec = MediaCodec.createByCodecName(name)
        val muxer = MediaMuxer(file.path,
            if (spec.isWebm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var codecStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            if (spec.rotation != 0) muxer.setOrientationHint(spec.rotation)
            val chromaWidth = (spec.codedWidth + 1) / 2
            val chromaHeight = (spec.codedHeight + 1) / 2
            val frameBytes = spec.codedWidth * spec.codedHeight + 2 * chromaWidth * chromaHeight
            val info = MediaCodec.BufferInfo()
            var track = -1
            var queued = 0
            var written = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                check(SystemClock.uptimeMillis() < deadline) {
                    "Encoding ${spec.name} exceeded its deadline: queued=$queued written=$written"
                }
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val pts = queued * 1_000_000L / spec.fps
                        if (queued == spec.frameCount) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val image = requireNotNull(codec.getInputImage(inIndex)) { "$name returned no input image" }
                            fillImage(image, codedFrame(spec, queued), spec.codedWidth, spec.codedHeight)
                            codec.queueInputBuffer(inIndex, 0, frameBytes, pts, 0)
                            queued++
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted) { "Output format changed twice" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val buffer = requireNotNull(codec.getOutputBuffer(outIndex))
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0) {
                        check(muxerStarted) { "Sample before output format" }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                        written++
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            check(written == spec.frameCount) { "${spec.name}: encoder wrote $written of ${spec.frameCount} frames" }
        } finally {
            if (codecStarted) codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
        return name
    }

    private fun fillImage(image: Image, argb: IntArray, w: Int, h: Int) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until h) {
            val base = row * yPlane.rowStride
            for (col in 0 until w) {
                val c = argb[row * w + col]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(base + col * yPlane.pixelStride, y.coerceIn(0, 255).toByte())
            }
        }
        for (row in 0 until (h + 1) / 2) {
            for (col in 0 until (w + 1) / 2) {
                val c = argb[(row * 2) * w + col * 2]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuffer.put(row * uPlane.rowStride + col * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                vBuffer.put(row * vPlane.rowStride + col * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
            }
        }
    }

    fun inspect(file: File): VideoFacts {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            var samples = 0
            var first = -1L
            var last = -1L
            while (extractor.sampleTime >= 0) {
                if (first < 0) first = extractor.sampleTime
                last = extractor.sampleTime
                samples++
                if (!extractor.advance()) break
            }
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            return VideoFacts(format.getString(MediaFormat.KEY_MIME) ?: "?", format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT), rotation, samples, first, last, duration)
        } finally {
            extractor.release()
        }
    }
}
