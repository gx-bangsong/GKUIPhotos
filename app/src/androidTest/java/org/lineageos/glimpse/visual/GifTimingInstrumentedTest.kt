/* Reproduces GIF frame-delay and video-to-GIF timing defects with independent fixtures.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.Manifest
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.glimpse.GifEditorActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.SettingsActivity
import org.lineageos.glimpse.ViewActivity
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.ui.dialogs.ImageToolboxBottomSheet
import org.lineageos.glimpse.utils.media.GifCodec
import org.lineageos.glimpse.utils.media.ImageToolbox
import org.lineageos.glimpse.utils.media.ToolboxResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Methods are split by the fix step that owns their behaviour (step2_*, step3_*,
 * step4_*). Baseline runs execute the full pipeline and publish measured values
 * as evidence, asserting only the harness; candidate runs assert fixed behaviour.
 */
@RunWith(AndroidJUnit4::class)
class GifTimingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver: ContentResolver get() = context.contentResolver
    private val device = UiDevice.getInstance(instrumentation)
    private val uiVersion = InstrumentationRegistry.getArguments().getString("uiVersion") ?: "candidate"
    private val isBaseline = uiVersion == "baseline"
    private val ownedUris = mutableListOf<Uri>()
    private var activity: Activity? = null

    private data class GifCase(
        val name: String, val tag: String, val video: VideoSpec, val seconds: Int, val fps: Int,
        val maxEdge: Int, val expectedWidth: Int, val expectedHeight: Int, val checkContent: Boolean,
    )

    private class Conversion(
        val case: GifCase,
        val gif: MinimalGif.Parsed?,
        /** Exact pixel-layer token (lzw=..., platform=...) or null when pixels decode and match the platform. */
        val pixelIssue: String?,
        val error: Throwable?,
        val decoded: List<Int?>,
        val probeTimesUs: List<Long>,
        val closestNulls: Int,
        val retrieverSize: String,
        val reportedSyncFallbacks: Int?,
        val elapsedMs: Long,
    )

    private data class Row(val uri: Uri, val name: String, val pending: Int, val path: String)

    /** Delegates to MediaStore but fails every write-mode open, after the row was inserted. */
    private class FailingWriteProvider(private val delegate: ContentResolver) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
            delegate.query(uri, projection, selection, selectionArgs, sortOrder)
        override fun getType(uri: Uri): String? = delegate.getType(uri)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = delegate.insert(uri, values)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
            delegate.delete(uri, selection, selectionArgs)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
            delegate.update(uri, values, selection, selectionArgs)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode.contains('w')) throw FileNotFoundException("Injected write failure for $uri")
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                return ParcelFileDescriptor.open(File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
            }
            return delegate.openFileDescriptor(uri, mode)
        }
    }

    // ------------------------------------------------------------------
    // Harness (asserted on baseline and candidate)
    // ------------------------------------------------------------------

    @Test fun harness_parserSelfCheck() {
        // Known-good GIFs built by the test's own writer (never GifCodec): both layers must be exact.
        val bytes = fixtureGif(listOf(10, 7, 2))
        val gif = MinimalGif.parse(bytes)
        assertEquals("Fixture raw delays", listOf(10, 7, 2), gif.delaysRaw)
        assertEquals("Fixture frame count", 3, gif.frames.size)
        assertEquals("Fixture image descriptors", 3, gif.imageDescriptors)
        assertEquals("Fixture width", 64, gif.width)
        assertEquals("Fixture height", 48, gif.height)
        assertTrue("Fixture loop block", gif.loopBlockPresent)
        assertEquals("Fixture loop count", 0, gif.loopCount)
        assertEquals("Fixture trailing bytes", 0, gif.trailingBytes)
        assertEquals("Fixture pixel layer: ${gif.lzwError}", null, gif.lzwError)
        gif.frames.forEachIndexed { i, _ ->
            assertEquals("Fixture body colour of frame $i", FIXTURE_PALETTE[i % 3], gif.pixels(i)[40 * 64 + 10])
            assertEquals("Fixture stripe colour of frame $i", FIXTURE_PALETTE[3], gif.pixels(i)[0])
        }
        assertEquals("Parser disagrees with the platform GIF decoder", 0, platformMismatch(bytes, gif))

        val lines = ArrayList<String>()
        compressedFixtures().forEach { fixture ->
            val compressed = MinimalGif.write(fixture.width, fixture.height, fixture.palette,
                listOf(fixture.indices to 4, fixture.indices.reversedArray() to 6), compress = true)
            val parsed = MinimalGif.parse(compressed)
            lines.add("${fixture.name}: ${parsed.structure()} ${parsed.pixelLayer()}")
            assertEquals("${fixture.name} raw delays", listOf(4, 6), parsed.delaysRaw)
            assertEquals("${fixture.name} pixel layer: ${parsed.lzwError}", null, parsed.lzwError)
            listOf(fixture.indices, fixture.indices.reversedArray()).forEachIndexed { f, indices ->
                val wrong = indices.indices.count { parsed.pixels(f)[it] != fixture.palette[indices[it].toInt() and 0xFF] }
                assertEquals("${fixture.name} frame $f pixels differ from the source indices", 0, wrong)
            }
            assertEquals("${fixture.name} parser disagrees with the platform GIF decoder", 0, platformMismatch(compressed, parsed))
            fixture.expectedMaxWidth?.let {
                assertEquals("${fixture.name} widest code", it, parsed.frames.maxOf { frame -> frame.maxCodeWidth })
            }
            if (fixture.needsMidStreamClear) {
                assertTrue("${fixture.name} needs a mid-stream clear code: ${parsed.pixelLayer()}",
                    parsed.frames.any { it.clearCodes >= 2 })
            }
        }
        publish("harness-parser", lines.joinToString("\n", postfix = "\n"))
    }

    @Test fun harness_syntheticVideosAreValid() {
        publish("encoder-probe", encoderProbe.joinToString("\n") + "\noddCase=${oddCase?.name ?: "none: no encoder supports ${ODD_WIDTH}x$ODD_HEIGHT"}\n")
        val lines = ArrayList<String>()
        cases.map { it.video }.distinctBy { it.name }.forEach { spec ->
            val file = videoFile(spec)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                listOf(0, spec.frameCount / 2, spec.frameCount - 1).forEach { k ->
                    val t = k * 1_000_000L / spec.fps
                    val bitmap = requireNotNull(retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)) {
                        "${spec.name}: platform retriever returned no frame at $t us"
                    }
                    val px = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val index = SyntheticVideo.decodeIndex(px, bitmap.width, bitmap.height)
                    lines.add("${spec.name} k=$k t=${t}us retrieverFrame=${bitmap.width}x${bitmap.height} " +
                        "index=$index redCorners=${SyntheticVideo.redCorners(px, bitmap.width, bitmap.height)}")
                    bitmap.recycle()
                    if (spec.rotation == 0) assertEquals("${spec.name} index decoded from platform frame at $t us", k, index)
                }
            } finally {
                retriever.release()
            }
        }
        publish("video-validation", lines.joinToString("\n", postfix = "\n"))
        cases.forEach { case ->
            val (w, h) = expectedDims(case.video.displayWidth, case.video.displayHeight, case.maxEdge)
            assertEquals("${case.name} expected size from the independent formula",
                case.expectedWidth to case.expectedHeight, w to h)
            val longest = maxOf(case.video.displayWidth, case.video.displayHeight)
            if (longest > case.maxEdge) {
                assertEquals("${case.name} fixture must scale exactly", 0L,
                    minOf(case.video.displayWidth, case.video.displayHeight).toLong() * case.maxEdge % longest)
            }
        }
    }

    // ------------------------------------------------------------------
    // Step 2: encoder writes centiseconds (cumulative rounding)
    // ------------------------------------------------------------------

    @Test fun step2_encoderWritesCentisecondDelays() {
        val inputs = listOf(listOf(100, 70, 20, 125), listOf(15, 15, 15))
        val expected = listOf(listOf(10, 7, 2, 13), listOf(2, 2, 2))
        val outputs = inputs.map { delaysMs ->
            val frames = delaysMs.mapIndexed { i, delay ->
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(FIXTURE_PALETTE[i % 4])
                GifCodec.GifFrame(bitmap, delay)
            }
            val out = ByteArrayOutputStream()
            GifCodec.encode(frames, out)
            frames.forEach { it.bitmap.recycle() }
            val bytes = out.toByteArray()
            // Structure layer must parse on both versions; pixel problems become evidence/violations.
            bytes to MinimalGif.parse(bytes)
        }
        val issues = outputs.map { (bytes, gif) -> pixelIssue(bytes, gif) }
        publish("step2-encoder", outputs.mapIndexed { i, (_, gif) ->
            "in$i inputMs=${inputs[i]} expectedRaw=${expected[i]} ${gif.structure()} ${gif.pixelLayer()} " +
                "pixelIssue=${issues[i]}"
        }.joinToString("\n", postfix = "\n"))
        if (isBaseline) return
        val violations = ArrayList<String>()
        outputs.forEachIndexed { i, (_, gif) ->
            if (gif.delaysRaw != expected[i]) violations.add("in$i raw=${gif.delaysRaw} exp=${expected[i]}")
            if (!gif.loopBlockPresent || gif.loopCount != 0) violations.add("in$i loop=${gif.loopBlockPresent}/${gif.loopCount}")
            issues[i]?.let { violations.add("in$i $it") }
        }
        assertTrue("Encoder delay violations: " + violations.joinToString("; "), violations.isEmpty())
    }

    @Test fun step2_editorRoundTripPreservesRawDelays() {
        val sourceBytes = fixtureGif(listOf(10, 7, 2))
        val source = MinimalGif.parse(sourceBytes)
        assertEquals("Fixture raw delays", listOf(10, 7, 2), source.delaysRaw)
        val sourceUri = insertGif(sourceBytes)
        val startedMs = System.currentTimeMillis()
        val output = exportThroughEditor(sourceUri, source.frames.size, startedMs)
        val bytes = try {
            requireNotNull(resolver.openInputStream(output)) { "Cannot open editor output" }.use { it.readBytes() }
        } finally {
            resolver.delete(output, null, null)
        }
        val gif = MinimalGif.parse(bytes)
        val issue = pixelIssue(bytes, gif)
        val identicalNeighbours = if (issue != null) -1
        else (1 until gif.frames.size).count { gif.pixels(it).contentEquals(gif.pixels(it - 1)) }
        publish("step2-editor-roundtrip", "rt sourceRaw=${source.delaysRaw} ${gif.structure()} ${gif.pixelLayer()} " +
            "pixelIssue=$issue identicalNeighbours=$identicalNeighbours\n")
        if (isBaseline) return
        val violations = ArrayList<String>()
        if (gif.delaysRaw != source.delaysRaw) violations.add("rt raw=${gif.delaysRaw} exp=${source.delaysRaw}")
        if (!gif.loopBlockPresent || gif.loopCount != 0) violations.add("rt loop=${gif.loopBlockPresent}/${gif.loopCount}")
        if (gif.width != source.width || gif.height != source.height) violations.add("rt size=${gif.width}x${gif.height}")
        if (issue != null) violations.add("rt $issue")
        else if (identicalNeighbours != 0) violations.add("rt identicalNeighbours=$identicalNeighbours")
        assertTrue("Editor round-trip violations: " + violations.joinToString("; "), violations.isEmpty())
    }

    // ------------------------------------------------------------------
    // Step 3: uniform sampling plan (plan B), coverage, size, estimate text
    // ------------------------------------------------------------------

    @Test fun step3_videoGifFollowsUniformPlan() {
        val results = cases.map { convert(it) }
        val violations = ArrayList<String>()
        if (results.any { it.gif != null && it.reportedSyncFallbacks == null }) violations.add("ToolboxResult lacks syncFallbackFrames")
        results.forEach { c ->
            val case = c.case
            val t = case.tag
            val gif = c.gif
            if (gif == null) {
                violations.add("$t error=${c.error?.javaClass?.simpleName}")
                return@forEach
            }
            val n = expectedFrames(case)
            val interval = intervalMs(case)
            val raw = gif.delaysRaw
            if (gif.frames.size != n) violations.add("$t n=${gif.frames.size}/$n")
            if (raw.any { abs(it - interval / 10.0) > 1.0 }) {
                violations.add("$t raw=${raw.minOrNull()}..${raw.maxOrNull()} exp=${String.format(Locale.US, "%.1f", interval / 10)}")
            }
            if (abs(gif.totalCs * 10.0 - case.seconds * 1000.0) >= interval) violations.add("$t sum=${gif.totalCs}/${case.seconds * 100}cs")
            if (gif.frames.size > MAX_FRAMES || gif.frames.size < 2 * case.seconds) violations.add("$t fps=${gif.frames.size}/${case.seconds}s")
            // Rounding only; which side is the width for rotated sources is step 4 (orientation).
            val rotated = case.video.rotation % 180 != 0
            val actualSize = if (rotated) listOf(gif.width, gif.height).sorted() else listOf(gif.width, gif.height)
            val wantedSize = if (rotated) listOf(case.expectedWidth, case.expectedHeight).sorted()
            else listOf(case.expectedWidth, case.expectedHeight)
            if (actualSize != wantedSize) violations.add("$t size=${gif.width}x${gif.height}/${case.expectedWidth}x${case.expectedHeight}")
            val shortSide = minOf(gif.width, gif.height).toDouble()
            val longSide = maxOf(gif.width, gif.height)
            val sourceRatio = maxOf(case.video.displayWidth, case.video.displayHeight).toDouble() /
                minOf(case.video.displayWidth, case.video.displayHeight)
            val aspectError = abs(longSide - shortSide * sourceRatio)
            if (aspectError >= 1.0) violations.add("$t aspect=${String.format(Locale.US, "%.2f", aspectError)}px")
            val issue = c.pixelIssue
            if (issue != null) {
                // Pixel-dependent checks are meaningless until the pixel layer decodes.
                violations.add("$t $issue")
                return@forEach
            }
            val identical = (1 until gif.frames.size).count { gif.pixels(it).contentEquals(gif.pixels(it - 1)) }
            if (identical > 0) violations.add("$t identicalNeighbours=$identical")
            if (case.checkContent) {
                val plan = planTimesUs(case)
                val first = c.decoded.firstOrNull()
                if (first == null || abs(first - expectedSourceIndex(case, plan.first())) > 1) violations.add("$t first#$first")
                val last = c.decoded.lastOrNull()
                val lastExpected = expectedSourceIndex(case, plan.last())
                if (last == null || abs(last - lastExpected) > 1) violations.add("$t last#$last/$lastExpected")
                if (c.decoded.any { it == null }) violations.add("$t undecodable=${c.decoded.count { it == null }}")
                val duplicates = (1 until c.decoded.size).count { c.decoded[it] != null && c.decoded[it] == c.decoded[it - 1] }
                if (duplicates > 0) violations.add("$t dup=$duplicates")
            }
        }
        publish("step3-plan", results.joinToString("\n", postfix = "\n") { summary(it) } +
            "violations=" + violations.joinToString("; ") + "\n")
        if (isBaseline) return
        assertTrue("Video GIF plan violations: " + violations.joinToString("; "), violations.isEmpty())
    }

    @Test fun step3_estimateShowsActualFrameRate() {
        val spec = L30_LONG
        val file = videoFile(spec)
        val media = Media(Uri.fromFile(file), MediaType.VIDEO, "video/mp4", Uri.EMPTY, null, file.name,
            false, false, Date(), Date(), spec.displayWidth, spec.displayHeight, 0, file.length())
        val host = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activity = host
        var sheet: ImageToolboxBottomSheet? = null
        var valueTo = -1f
        var reduced = ""
        var normal = ""
        try {
            runOnMainWithTimeout(instrumentation, "show video toolbox") {
                val themed = ContextThemeWrapper(host, R.style.Theme_Glimpse_MediaViewer)
                sheet = ImageToolboxBottomSheet(themed, host as LifecycleOwner, media).also { it.show() }
            }
            waitForBoundedUiIdle(instrumentation, device, "video toolbox shown")
            runOnMainWithTimeout(instrumentation, "read estimates") {
                val dialog = requireNotNull(sheet)
                val slider = requireNotNull(dialog.findViewById<Slider>(R.id.gifDurationSlider)) { "Duration slider missing" }
                val chips = requireNotNull(dialog.findViewById<ChipGroup>(R.id.fpsChipGroup)) { "FPS chips missing" }
                val estimate = requireNotNull(dialog.findViewById<TextView>(R.id.videoGifEstimate)) { "Estimate missing" }
                valueTo = slider.valueTo
                fun choose(seconds: Int, fps: Int): String {
                    slider.value = seconds.toFloat()
                    val chip = (0 until chips.childCount).map { chips.getChildAt(it) }.filterIsInstance<Chip>()
                        .first { it.text.toString().startsWith("$fps ") }
                    chip.performClick()
                    return estimate.text.toString()
                }
                reduced = choose(10, 15)
                normal = choose(3, 10)
            }
        } finally {
            runOnMainWithTimeout(instrumentation, "dismiss video toolbox") { sheet?.dismiss() }
        }
        val reducedId = host.resources.getIdentifier("toolbox_video_to_gif_estimate_reduced", "string", context.packageName)
        val normalId = host.resources.getIdentifier("toolbox_video_to_gif_estimate", "string", context.packageName)
        val locale = host.resources.configuration.locales[0]
        val actualFps = String.format(locale, "%.1f", 36 / 10.0)
        val expectedReduced = if (reducedId != 0) host.getString(reducedId, 10, actualFps, 15, 480, 36) else "<missing resource>"
        val expectedNormal = if (normalId != 0) host.getString(normalId, 3, 10, 480, 30) else "<missing resource>"
        publish("step3-estimate", "sliderValueTo=$valueTo\nreduced(10s,15fps)=$reduced\nexpectedReduced=$expectedReduced\n" +
            "normal(3s,10fps)=$normal\nexpectedNormal=$expectedNormal\n")
        if (isBaseline) return
        val violations = ArrayList<String>()
        if (valueTo != 18f) violations.add("sliderMax=$valueTo/18")
        if (reduced != expectedReduced || !reduced.contains(actualFps)) violations.add("reduced='$reduced' exp='$expectedReduced'")
        if (normal != expectedNormal) violations.add("normal='$normal' exp='$expectedNormal'")
        assertTrue("Estimate violations: " + violations.joinToString("; "), violations.isEmpty())
    }

    // ------------------------------------------------------------------
    // Step 4: orientation, pending-row cleanup, gallery animation
    // ------------------------------------------------------------------

    @Test fun step4_orientationIsUpright() {
        val results = cases.map { convert(it) }
        val violations = ArrayList<String>()
        val lines = ArrayList<String>()
        results.forEach { c ->
            val gif = c.gif
            if (gif == null) {
                violations.add("${c.case.tag} error=${c.error?.javaClass?.simpleName}")
                return@forEach
            }
            if (gif.width != c.case.expectedWidth || gif.height != c.case.expectedHeight) {
                violations.add("${c.case.tag} size=${gif.width}x${gif.height}/${c.case.expectedWidth}x${c.case.expectedHeight}")
            }
            if (c.pixelIssue != null) {
                lines.add("${c.case.name} gif=${gif.width}x${gif.height} retrieverFrame=${c.retrieverSize} pixelIssue=${c.pixelIssue}")
                violations.add("${c.case.tag} pixels undecodable")
                return@forEach
            }
            listOf(0, gif.frames.lastIndex).distinct().forEach { i ->
                val corners = SyntheticVideo.redCorners(gif.pixels(i), gif.width, gif.height)
                lines.add("${c.case.name} frame=$i gif=${gif.width}x${gif.height} retrieverFrame=${c.retrieverSize} redCorners=$corners")
                if (corners != listOf("TL")) violations.add("${c.case.tag} f$i red=$corners")
            }
        }
        publish("step4-orientation", lines.joinToString("\n", postfix = "\n") + "violations=" + violations.joinToString("; ") + "\n")
        if (isBaseline) return
        assertTrue("Orientation violations: " + violations.joinToString("; "), violations.isEmpty())
    }

    @Test fun step4_writeFailureLeavesNoPendingRow() {
        val spec = L60
        val file = videoFile(spec)
        val prefix = file.nameWithoutExtension + "_gif_"
        fun outputs() = ownRows("${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("Pictures/Glimpse/", "$prefix%"))
        val before = outputs().map { it.uri }.toSet()
        val appContext = context
        val provider = FailingWriteProvider(appContext.contentResolver)
        // attachInfo records this process' uid, so in-process calls pass the permission checks.
        provider.attachInfo(appContext, ProviderInfo().apply {
            authority = appContext.packageName + ".gif-timing-failing-writes"
            exported = false
        })
        val failing = object : ContextWrapper(appContext) {
            private val wrapped = ContentResolver.wrap(provider)
            override fun getContentResolver(): ContentResolver = wrapped
        }
        val convertWithFailingWrites = {
            ImageToolbox.videoToGif(failing, Uri.fromFile(file), durationSeconds = 1, fps = 5, maxEdge = 240)
        }
        var leaked: List<Row> = emptyList()
        var error: Throwable? = null
        try {
            error = if (isBaseline) {
                runCatching { convertWithFailingWrites() }.exceptionOrNull()
            } else {
                assertThrows(Exception::class.java) { convertWithFailingWrites() }
            }
            leaked = outputs().filter { it.uri !in before }
        } finally {
            outputs().filter { it.uri !in before }.forEach { resolver.delete(it.uri, null, null) }
        }
        publish("step4-pending", "error=${error?.javaClass?.name}: ${error?.message}\nleakedRows=$leaked\n")
        if (isBaseline) return
        assertTrue("Rows left after a failed write: " + leaked.joinToString { "${it.name} is_pending=${it.pending}" }, leaked.isEmpty())
    }

    @Test fun step4_galleryAnimatesGif() {
        val permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val uri = insertGif(fixtureGif(listOf(20, 20, 20)))
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/gif")
            .setClass(context, ViewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val viewer = instrumentation.startActivitySync(intent)
        activity = viewer
        val frames = LinkedHashSet<Int>()
        var drawableName = "none"
        var running = false
        var frameCount = -1
        val animated = waitOnMain(15_000) {
            val drawable = shownImageView(viewer)?.drawable
            drawableName = drawable?.javaClass?.name ?: "none"
            running = (drawable as? Animatable)?.isRunning == true
            val gif = drawable as? GifDrawable ?: drawable?.current as? GifDrawable
            if (gif != null) {
                frameCount = gif.frameCount
                frames.add(gif.frameIndex)
            }
            frames.size >= 2
        }
        val observation = "drawable=$drawableName running=$running frameCount=$frameCount framesSeen=$frames animated=$animated"
        publish("step4-gallery", observation + "\n")
        if (isBaseline) return
        assertTrue("Gallery GIF did not animate: $observation", animated)
    }

    // ------------------------------------------------------------------
    // Pre-2a LZW evidence (tests only). New code uses fully qualified names so that the
    // import block, and with it every assertion line listed in the expected-failure doc, stays put.
    // ------------------------------------------------------------------

    /**
     * Evidence only, on both uiVersions, never asserted (2a turns threeWayCompare into assertions):
     * how GifCodec output reads in the independent parser, ImageDecoder, GifCodec.decode and Glide,
     * and whether the pre-fix rule (a frame breaks once it needs 2^m - 1 or more data codes) holds.
     */
    @Test fun lzw_decoderEvidence() {
        val lines = ArrayList<String>()
        lzwCases().forEach { c ->
            val source = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
            source.setPixels(c.argb, 0, c.width, 0, 0, c.width, c.height)
            val bytes = ByteArrayOutputStream().also { GifCodec.encode(listOf(GifCodec.GifFrame(source, 100)), it) }.toByteArray()
            source.recycle()
            val gif = MinimalGif.parse(bytes)
            val m = gif.frames.first().minCodeSize
            val codes = lzwDataCodes(c.argb, m)
            val predictedOk = codes <= (1 shl m) - 2
            val check = threeWayCompare(bytes, c.width, c.height, listOf(c.argb))
            val observedOk = check.disagreements.isEmpty() && check.decoderIssues.isEmpty()
            val glide = try {
                glideFirstFrame(bytes)?.let { bitmap ->
                    try {
                        if (bitmap.width != c.width || bitmap.height != c.height) "size:${bitmap.width}x${bitmap.height}"
                        else comparePixels(c.argb, IntArray(c.width * c.height).also {
                            bitmap.getPixels(it, 0, c.width, 0, 0, c.width, c.height)
                        })
                    } finally {
                        bitmap.recycle()
                    }
                } ?: "null"
            } catch (e: Exception) {
                "error:${e.javaClass.simpleName}"
            }
            lines.add("${c.name} size=${c.width}x${c.height} colours=${c.argb.distinct().size} m=$m dataCodes=$codes " +
                "rule=${if (predictedOk) "conformant" else "breaks"} observed=${if (observedOk) "conformant" else "breaks"} " +
                "ruleMatches=${predictedOk == observedOk} maxCodeWidth=${gif.frames.first().maxCodeWidth} " +
                "clearCodes=${gif.frames.first().clearCodes} endCode=${gif.frames.first().endCode} " +
                "compared=${check.compared}/${check.expectedCompared} disagreements=${check.disagreements.size} " +
                "first=${check.disagreements.take(3)} issues=${check.decoderIssues} glide=$glide")
        }
        publish("lzw-decoders", lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * Import path of external GIFs: GifEditorActivity reads them with GifCodec.decode. Known-good
     * files from the test's own standard writer (never GifCodec) must decode to the exact source
     * pixels and raw delay x 10 ms. Baseline records only; candidate asserts.
     */
    @Test fun lzw_externalGifImportDecodesExactly() {
        val violations = ArrayList<String>()
        val lines = ArrayList<String>()
        val inputs = compressedFixtures().map { f ->
            Triple(f, listOf(f.indices to 4, f.indices.reversedArray() to 6), true)
        } + listOf(Triple(
            Fixture("literal3", 64, 48, FIXTURE_PALETTE,
                ByteArray(64 * 48) { p -> (if (p / 64 < 8) 3 else 0).toByte() }, null, false),
            (0 until 3).map { i -> ByteArray(64 * 48) { p -> (if (p / 64 < 8) 3 else i % 3).toByte() } to listOf(10, 7, 2)[i] },
            false,
        ))
        inputs.forEach { (f, frames, compress) ->
            val bytes = MinimalGif.write(f.width, f.height, f.palette, frames, compress = compress)
            val parsed = MinimalGif.parse(bytes)
            val decoded = GifCodec.decode(java.io.ByteArrayInputStream(bytes))
            val delaysMs = decoded.map { it.delayMs }
            val expectedMs = frames.map { it.second * 10 }
            val wrong = frames.mapIndexed { i, (indices, _) ->
                val bitmap = decoded.getOrNull(i)?.bitmap ?: return@mapIndexed indices.size
                if (bitmap.width != f.width || bitmap.height != f.height) return@mapIndexed indices.size
                val px = IntArray(f.width * f.height).also { bitmap.getPixels(it, 0, f.width, 0, 0, f.width, f.height) }
                indices.indices.count { px[it] != f.palette[indices[it].toInt() and 0xFF] }
            }
            lines.add("${f.name} ${parsed.structure()} ${parsed.pixelLayer()} decodedFrames=${decoded.size} " +
                "endCodes=${parsed.frames.map { it.endCode }} delaysMs=$delaysMs expectedMs=$expectedMs wrongPixels=$wrong")
            if (decoded.size != frames.size) violations.add("${f.name} frames=${decoded.size}/${frames.size}")
            if (delaysMs != expectedMs) violations.add("${f.name} delays=$delaysMs/$expectedMs")
            wrong.forEachIndexed { i, n -> if (n != 0) violations.add("${f.name} f$i pixels=$n/${f.width * f.height}") }
        }
        publish("lzw-external-import", lines.joinToString("\n", postfix = "\n"))
        if (isBaseline) return
        assertTrue("External GIF import violations: ${violations.joinToString("; ")}", violations.isEmpty())
    }

    /**
     * Step 2a: GifCodec output must read pixel-identically in the independent parser, ImageDecoder
     * and GifCodec.decode (threeWayCompare), for 1-, 2- and 256-colour frames, both sides of every
     * code-width boundary, and a frame that needs 12-bit codes and a mid-stream clear code.
     * Candidate: no disagreement and no decoder issue. Baseline (772071a): the same check on the
     * pre-fix encoder must report problems, which proves it fails before the fix.
     */
    @Test fun step2a_lzwOutputIsPixelIdenticalAcrossDecoders() {
        val cases = lzwCases()
        val problems = ArrayList<String>()
        val structural = ArrayList<String>()
        val lines = ArrayList<String>()
        var compared = 0
        cases.forEach { c ->
            val bytes = gifCodecSingleFrame(c)
            val check = threeWayCompare(bytes, c.width, c.height, listOf(c.argb))
            compared += check.compared
            val first = MinimalGif.parse(bytes).frames.first()
            val caseProblems = check.decoderIssues.map { "${c.name} $it" } + check.disagreements.map { "${c.name} $it" }
            problems.addAll(caseProblems)
            lines.add("${c.name} compared=${check.compared} problems=${caseProblems.size} issues=${check.decoderIssues} " +
                "disagreements=${check.disagreements.size} first=${check.disagreements.take(3)} " +
                "maxCodeWidth=${first.maxCodeWidth} clearCodes=${first.clearCodes} endCode=${first.endCode}")
            if (c.name == "random256" && (first.maxCodeWidth != 12 || first.clearCodes < 2)) {
                structural.add("random256 maxCodeWidth=${first.maxCodeWidth} clearCodes=${first.clearCodes}")
            }
        }
        val expectedCompared = cases.sumOf { it.width * it.height }
        publish("step2a-threeway", lines.joinToString("\n", postfix = "\n") +
            "compared=$compared/$expectedCompared problems=${problems.size} first=${problems.take(5)}\n")
        assertEquals("Pixels compared by threeWayCompare", expectedCompared, compared)
        if (isBaseline) {
            assertTrue("Three-way check found no problem in the pre-fix encoder output", problems.isNotEmpty())
            return
        }
        val violations = structural + problems
        assertTrue("Three-way LZW violations (${violations.size}): " + violations.take(10).joinToString("; "),
            violations.isEmpty())
    }

    /** One GifCodec-encoded single-frame GIF for [c] (100 ms delay; delays are not under test here). */
    private fun gifCodecSingleFrame(c: LzwCase): ByteArray {
        val source = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
        source.setPixels(c.argb, 0, c.width, 0, 0, c.width, c.height)
        return try {
            ByteArrayOutputStream().also { GifCodec.encode(listOf(GifCodec.GifFrame(source, 100)), it) }.toByteArray()
        } finally {
            source.recycle()
        }
    }

    private class LzwCase(val name: String, val width: Int, val height: Int, val argb: IntArray)

    /** 256 colours that stay distinct in GifCodec's 5-5-5 lookup cache (GifCodec.kt:364, 413-423). */
    private fun binSafeColour(i: Int): Int = ALPHA or
        ((((i and 7) shl 5) + 8) shl 16) or (((((i shr 3) and 7) shl 5) + 8) shl 8) or (((i shr 6) shl 6) + 8)

    /**
     * Single-frame GifCodec inputs: 1-colour runs whose data-code count sits just below, at and
     * above 2^2 - 1; a 2-colour frame; walks that never repeat a colour pair (one data code per
     * pixel) at both sides of every 9→10→11→12 boundary; and a 256-colour random frame that needs
     * 12-bit codes and table clears.
     */
    private fun lzwCases(): List<LzwCase> {
        var seed = 0x5DEECE66DL
        fun nextInt(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33) % bound).toInt()
        }
        fun walk(n: Int): IntArray {
            var x = 0
            var stride = 1
            return IntArray(n) { i ->
                val colour = binSafeColour(x)
                if ((i + 1) % 256 == 0) stride += 2
                x = (x + stride) and 0xFF
                colour
            }
        }
        val solid = binSafeColour(77)
        return listOf(
            LzwCase("solid_codes2", 3, 1, IntArray(3) { solid }),
            LzwCase("solid_codes3", 4, 1, IntArray(4) { solid }),
            LzwCase("solid_codes4", 7, 1, IntArray(7) { solid }),
            LzwCase("solid64", 64, 64, IntArray(64 * 64) { solid }),
            LzwCase("two64", 64, 64, IntArray(64 * 64) { binSafeColour(if (nextInt(2) == 0) 10 else 200) }),
        ) + listOf(254, 255, 256, 767, 768, 1791, 1792).map { n -> LzwCase("walk$n", n, 1, walk(n)) } +
            LzwCase("random256", 128, 96, IntArray(128 * 96) { binSafeColour(nextInt(256)) })
    }

    /** Data codes (excluding clear/end) of a greedy LZW parse; independent of code widths. */
    private fun lzwDataCodes(argb: IntArray, minCodeSize: Int): Int {
        val dense = HashMap<Int, Int>()
        val symbols = IntArray(argb.size) { dense.getOrPut(argb[it]) { dense.size } }
        val first = (1 shl minCodeSize) + 2
        val table = HashMap<Long, Int>()
        var free = first
        var prefix = symbols[0]
        var codes = 0
        for (i in 1 until symbols.size) {
            val key = (prefix.toLong() shl 16) or symbols[i].toLong()
            val hit = table[key]
            if (hit != null) {
                prefix = hit
                continue
            }
            codes++
            if (free < 4096) {
                table[key] = free++
            } else {
                table.clear()
                free = first
            }
            prefix = symbols[i]
        }
        return codes + 1
    }

    private fun comparePixels(expected: IntArray, actual: IntArray): String {
        val wrong = expected.indices.filter { expected[it] != actual[it] }
        if (wrong.isEmpty()) return "ok"
        val i = wrong.first()
        return "mismatch:${wrong.size}/${expected.size}(first:$i got:%08x exp:%08x)".format(actual[i], expected[i])
    }

    /** First frame exactly as the gallery's Glide GIF pipeline decodes it, with a bounded wait. */
    private fun glideFirstFrame(bytes: ByteArray): Bitmap? {
        val target = com.bumptech.glide.Glide.with(context).asGif().load(bytes)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .submit()
        return try {
            target.get(10, TimeUnit.SECONDS).firstFrame?.let { it.copy(Bitmap.Config.ARGB_8888, false) }
        } finally {
            com.bumptech.glide.Glide.with(context).clear(target)
        }
    }

    /** One pixel where source, independent parser, platform and GifCodec do not all agree (null = no pixel). */
    private class PixelDisagreement(
        val frame: Int, val x: Int, val y: Int, val source: Int,
        val independent: Int?, val platform: Int?, val gifCodec: Int?,
    ) {
        override fun toString() = "f$frame($x,$y) src=${hex(source)} independent=${hex(independent)} " +
            "platform=${hex(platform)} gifCodec=${hex(gifCodec)}"
    }

    /** [compared] counts every visited pixel; it must equal [expectedCompared] = width x height x frames. */
    private class ThreeWay(
        val compared: Int, val expectedCompared: Int,
        val disagreements: List<PixelDisagreement>, val decoderIssues: List<String>,
    )

    /**
     * Pixel-exact comparison of one GIF across the independent parser (MinimalGif), the platform
     * decoder (ImageDecoder) and GifCodec.decode, each against the source pixels. Every pixel of
     * every frame is visited and counted. ImageDecoder exposes only the first frame, so inputs are
     * single-frame; GifCodec compresses every frame with the same lzwEncode (GifCodec.kt:426).
     * Stream-level problems (independent LZW error, missing end code, platform partial decode,
     * exceptions, frame counts, sizes) go to [ThreeWay.decoderIssues]. Only APIs present in both
     * 772071a and the candidate are used, so baseline and candidate run the same check.
     */
    private fun threeWayCompare(bytes: ByteArray, width: Int, height: Int, sourceFrames: List<IntArray>): ThreeWay {
        require(sourceFrames.size == 1) { "ImageDecoder only exposes frame 0; got ${sourceFrames.size} frames" }
        val issues = ArrayList<String>()
        val independent: IntArray? = try {
            val gif = MinimalGif.parse(bytes)
            if (gif.frames.size != 1) issues.add("independent frames=${gif.frames.size}")
            if (gif.width != width || gif.height != height) issues.add("independent size=${gif.width}x${gif.height}")
            gif.frames.forEachIndexed { i, frame -> if (frame.endCode != "end") issues.add("independent f$i endCode=${frame.endCode}") }
            gif.lzwError?.let { issues.add("independent lzw:$it") }
            gif.frames.firstOrNull()?.pixels
        } catch (e: Exception) {
            issues.add("independent error:${e.javaClass.simpleName}")
            null
        }
        fun pixelsOf(name: String, bitmap: Bitmap?): IntArray? {
            if (bitmap == null) {
                issues.add("$name null")
                return null
            }
            try {
                if (bitmap.width != width || bitmap.height != height) {
                    issues.add("$name size=${bitmap.width}x${bitmap.height}")
                    return null
                }
                return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
            } finally {
                bitmap.recycle()
            }
        }
        val platform: IntArray? = try {
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            ) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { e -> issues.add("platform partial:error${e.error}"); true }
            }
            pixelsOf("platform", bitmap)
        } catch (e: Exception) {
            issues.add("platform error:${e.javaClass.simpleName}")
            null
        }
        val gifCodec: IntArray? = try {
            val frames = GifCodec.decode(java.io.ByteArrayInputStream(bytes))
            if (frames.size != 1) issues.add("gifCodec frames=${frames.size}")
            pixelsOf("gifCodec", frames.firstOrNull()?.bitmap)
        } catch (e: Exception) {
            issues.add("gifCodec error:${e.javaClass.simpleName}")
            null
        }
        val disagreements = ArrayList<PixelDisagreement>()
        var compared = 0
        sourceFrames.forEachIndexed { f, src ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    compared++
                    val a = independent?.get(i)
                    val b = platform?.get(i)
                    val c = gifCodec?.get(i)
                    if (a != src[i] || b != src[i] || c != src[i]) {
                        disagreements.add(PixelDisagreement(f, x, y, src[i], a, b, c))
                    }
                }
            }
        }
        return ThreeWay(compared, width * height * sourceFrames.size, disagreements, issues)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @Before fun removeLeftovers() {
        ownRows("${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf(FIXTURE_DIR)).forEach {
            resolver.delete(it.uri, null, null)
        }
    }

    @After fun cleanup() {
        activity?.let { a -> runOnMainWithTimeout(instrumentation, "finish ${a.javaClass.simpleName}") { a.finish() } }
        ownedUris.forEach { resolver.delete(it, null, null) }
    }

    private fun fixtureGif(delays: List<Int>): ByteArray {
        val w = 64
        val h = 48
        val frames = delays.mapIndexed { i, delay ->
            ByteArray(w * h) { p -> (if (p / w < 8) 3 else i % 3).toByte() } to delay
        }
        return MinimalGif.write(w, h, FIXTURE_PALETTE, frames)
    }

    private fun insertGif(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "gif_fixture_${UUID.randomUUID()}.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, FIXTURE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        ownedUris.add(uri)
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        check(resolver.update(uri, values, null, null) == 1) { "Fixture GIF was not published" }
        return uri
    }

    /** Matching rows visible to this package, including its own pending rows. */
    private fun ownRows(selection: String, args: Array<String>): List<Row> {
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH)
        val rows = ArrayList<Row>()
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, query, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(Row(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)),
                    cursor.getString(1) ?: "", cursor.getInt(2), cursor.getString(3) ?: ""))
            }
        }
        return rows
    }

    private fun publish(label: String, text: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "gif-$uiVersion-$label.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(text.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        check(resolver.update(uri, values, null, null) == 1) { "Evidence $label was not published" }
    }

    /**
     * Pixels of frame 0 that differ between [gif] and the platform decoder, or -1
     * when the platform decoder cannot decode the bytes. Requires a decoded pixel layer.
     */
    private fun platformMismatch(bytes: ByteArray, gif: MinimalGif.Parsed): Int {
        val ours = gif.pixels(0)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return -1
        try {
            if (bitmap.width != gif.width || bitmap.height != gif.height) return gif.width * gif.height
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return px.indices.count { (px[it] or ALPHA) != (ours[it] or ALPHA) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Exact pixel-layer token for assertion messages: `lzw=frame:<n> offset:<n> width:<n>` or `platform=...`. */
    private fun pixelIssue(bytes: ByteArray, gif: MinimalGif.Parsed): String? {
        gif.lzwError?.let { return it.token }
        return when (val mismatch = platformMismatch(bytes, gif)) {
            0 -> null
            -1 -> "platform=undecodable"
            else -> "platform=${mismatch}px"
        }
    }

    private class Fixture(val name: String, val width: Int, val height: Int, val palette: IntArray,
        val indices: ByteArray, val expectedMaxWidth: Int?, val needsMidStreamClear: Boolean)

    /** Deterministic known-good inputs covering 1, 2 and 256 colours and code-width boundaries. */
    private fun compressedFixtures(): List<Fixture> {
        var seed = 0x2545F491L
        fun nextInt(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33) % bound).toInt()
        }
        fun palette(size: Int) = IntArray(size) { i ->
            (0xFF shl 24) or (((i * 37 + 11) and 0xFF) shl 16) or (((i * 91 + 3) and 0xFF) shl 8) or ((i * 13 + 5) and 0xFF)
        }
        return listOf(
            Fixture("solid1", 64, 64, palette(4), ByteArray(64 * 64) { 1 }, null, false),
            Fixture("two2", 64, 64, palette(2), ByteArray(64 * 64) { nextInt(2).toByte() }, null, false),
            Fixture("random256", 128, 96, palette(256), ByteArray(128 * 96) { nextInt(256).toByte() }, 12, true),
            // Smooth gradient: few new strings per row, so widths step 9→10→11→12 one at a time.
            Fixture("gradient256", 240, 135, palette(256), ByteArray(240 * 135) { ((it % 240) * 256 / 240).toByte() }, 12, false),
        )
    }

    private fun <T> callWithTimeout(stage: String, seconds: Long, block: () -> T): T {
        val task = FutureTask<T> { block() }
        Thread(task, "gif-timing-worker").apply { isDaemon = true; start() }
        try {
            return task.get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            throw AssertionError("$stage did not finish within $seconds s", e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Poll [predicate] on the main thread until true or [timeoutMs]; never blocks the main queue. */
    private fun waitOnMain(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val handler = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        val satisfied = AtomicBoolean(false)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val check = object : Runnable {
            override fun run() {
                when {
                    predicate() -> { satisfied.set(true); done.countDown() }
                    SystemClock.uptimeMillis() >= deadline -> done.countDown()
                    else -> handler.postDelayed(this, 50)
                }
            }
        }
        handler.post(check)
        val finished = done.await(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
        handler.removeCallbacks(check)
        return finished && satisfied.get()
    }

    private fun shownImageView(activity: Activity): ImageView? {
        val visible = Rect()
        fun search(view: View): ImageView? {
            if (view.id == R.id.imageView && view is ImageView && view.isShown && view.getGlobalVisibleRect(visible) &&
                visible.width() > 0 && visible.height() > 0) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return search(activity.window.decorView)
    }

    private fun exportThroughEditor(source: Uri, frameCount: Int, startedMs: Long): Uri {
        val editor = instrumentation.startActivitySync(
            GifEditorActivity.createIntent(context, source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activity = editor
        assertTrue("GIF editor did not decode $frameCount frames within 20 s", waitOnMain(20_000) {
            editor.findViewById<SeekBar>(R.id.frameSeekBar).max == frameCount - 1
        })
        val changed = Semaphore(0)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { changed.release() }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        try {
            runOnMainWithTimeout(instrumentation, "trigger GIF export") {
                val toolbar = editor.findViewById<Toolbar>(R.id.toolbar)
                check(toolbar.menu.performIdentifierAction(R.id.action_export, 0)) { "Export menu item was not handled" }
            }
            val deadline = SystemClock.uptimeMillis() + 30_000
            var found: Uri? = null
            while (found == null) {
                found = editorOutputs(startedMs).firstOrNull()?.uri
                if (found == null) {
                    val remaining = deadline - SystemClock.uptimeMillis()
                    assertTrue("GIF editor export did not publish a GIF within 30 s",
                        remaining > 0 && changed.tryAcquire(remaining, TimeUnit.MILLISECONDS))
                }
            }
            return requireNotNull(found)
        } finally {
            resolver.unregisterContentObserver(observer)
        }
    }

    private fun editorOutputs(startedMs: Long): List<Row> =
        ownRows("${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("Pictures/Glimpse/", "Glimpse_GIF_%")).filter { row ->
            row.pending == 0 &&
                (row.name.substringAfter("Glimpse_GIF_").substringBefore(".gif").toLongOrNull() ?: 0L) >= startedMs
        }

    private fun expectedFrames(case: GifCase) = minOf(MAX_FRAMES, case.seconds * case.fps)

    private fun intervalMs(case: GifCase) = case.seconds * 1000.0 / expectedFrames(case)

    private fun planTimesUs(case: GifCase): List<Long> {
        val n = expectedFrames(case)
        val durationUs = case.seconds * 1_000_000L
        return (0 until n).map { it * durationUs / n }
    }

    /** The pre-fix schedule (ImageToolbox.kt:384–413 at 1581a7b), used only to probe baseline fallbacks. */
    private fun legacyTimesUs(case: GifCase): List<Long> {
        val intervalUs = 1_000_000L / case.fps.coerceIn(1, 15)
        val windowUs = case.seconds * 1_000_000L
        val times = ArrayList<Long>()
        var t = 0L
        while (t < windowUs && times.size < MAX_FRAMES) {
            times.add(t)
            t += intervalUs
        }
        return times
    }

    private fun expectedSourceIndex(case: GifCase, timeUs: Long): Int =
        Math.round(timeUs * case.video.fps / 1_000_000.0).toInt()

    private fun expectedDims(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return w to h
        return (w.toLong() * maxEdge / longest).toInt() to (h.toLong() * maxEdge / longest).toInt()
    }

    private fun videoFile(spec: VideoSpec): File {
        return synchronized(videoFiles) {
            val cached = videoFiles[spec.name]
            if (cached != null && cached.isFile && cached.length() > 0) return cached
            val directory = File(context.cacheDir, "gif-timing-videos").apply { mkdirs() }
            val file = File(directory, spec.name + if (spec.isWebm) ".webm" else ".mp4")
            file.delete()
            val started = SystemClock.uptimeMillis()
            val encoder = SyntheticVideo.encode(spec, file, started + 180_000)
            val facts = SyntheticVideo.inspect(file)
            publish("video-${spec.name}", "spec=$spec\nencoder=$encoder\nencodeMs=${SystemClock.uptimeMillis() - started}\nfacts=$facts\n")
            assertEquals("${spec.name} sample count", spec.frameCount, facts.samples)
            assertEquals("${spec.name} coded width", spec.codedWidth, facts.width)
            assertEquals("${spec.name} coded height", spec.codedHeight, facts.height)
            assertEquals("${spec.name} rotation metadata", spec.rotation, facts.rotation)
            val lastExpected = (spec.frameCount - 1) * 1_000_000L / spec.fps
            assertTrue("${spec.name} last sample at ${facts.lastPtsUs} us, expected $lastExpected us",
                abs(facts.lastPtsUs - lastExpected) <= 1_000L)
            videoFiles[spec.name] = file
            file
        }
    }

    private fun convert(case: GifCase): Conversion {
        synchronized(conversions) { conversions[case.name]?.let { return it } }
        val file = videoFile(case.video)
        val source = Uri.fromFile(file)
        val started = SystemClock.uptimeMillis()
        val convertOnce = {
            ImageToolbox.videoToGif(context, source, durationSeconds = case.seconds, fps = case.fps, maxEdge = case.maxEdge)
        }
        val result: ToolboxResult
        if (isBaseline) {
            val attempt = runCatching { callWithTimeout("videoToGif ${case.name}", 180, convertOnce) }
            val failure = attempt.exceptionOrNull()
            if (failure != null) {
                // Baseline only: a legacy failure is evidence, not a harness failure.
                val failed = Conversion(case, null, null, failure, emptyList(), emptyList(), 0, "", null,
                    SystemClock.uptimeMillis() - started)
                publish("conversion-${case.name}", summary(failed) + "\n")
                synchronized(conversions) { conversions[case.name] = failed }
                return failed
            }
            result = attempt.getOrThrow()
        } else {
            result = callWithTimeout("videoToGif ${case.name}", 180, convertOnce)
        }
        val elapsed = SystemClock.uptimeMillis() - started
        val output = requireNotNull(result.uri) { "videoToGif returned no MediaStore uri for ${case.name}" }
        val bytes = try {
            requireNotNull(resolver.openInputStream(output)) { "Cannot open $output" }.use { it.readBytes() }
        } finally {
            resolver.delete(output, null, null)
        }
        val gif = MinimalGif.parse(bytes)
        val issue = pixelIssue(bytes, gif)
        val decoded: List<Int?> = if (issue != null) emptyList()
        else gif.frames.indices.map { SyntheticVideo.decodeIndex(gif.pixels(it), gif.width, gif.height) }
        val times = if (isBaseline) legacyTimesUs(case) else planTimesUs(case)
        val retriever = MediaMetadataRetriever()
        var nulls = 0
        var retrieverSize = ""
        try {
            retriever.setDataSource(file.path)
            times.forEach { t ->
                val bitmap = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap == null) {
                    nulls++
                } else {
                    if (retrieverSize.isEmpty()) retrieverSize = "${bitmap.width}x${bitmap.height}"
                    bitmap.recycle()
                }
            }
        } finally {
            retriever.release()
        }
        val reported = result.javaClass.methods.firstOrNull { it.name == "getSyncFallbackFrames" && it.parameterCount == 0 }
            ?.invoke(result) as? Int
        val conversion = Conversion(case, gif, issue, null, decoded, times, nulls, retrieverSize, reported, elapsed)
        publish("conversion-${case.name}", summary(conversion) + "\n")
        synchronized(conversions) { conversions[case.name] = conversion }
        return conversion
    }

    private fun summary(c: Conversion): String {
        val case = c.case
        val head = "case=${case.name} video=${case.video.name} request=${case.seconds}s@${case.fps}fps maxEdge=${case.maxEdge}"
        val gif = c.gif ?: return "$head error=${c.error?.javaClass?.name}: ${c.error?.message} elapsedMs=${c.elapsedMs}"
        val n = expectedFrames(case)
        val plan = planTimesUs(case)
        val duplicates = (1 until c.decoded.size).count { c.decoded[it] != null && c.decoded[it] == c.decoded[it - 1] }
        return "$head gif=${gif.structure()} ${gif.pixelLayer()} pixelIssue=${c.pixelIssue} " +
            "expectedFrames=$n expectedFps=${String.format(Locale.US, "%.2f", n / case.seconds.toDouble())} " +
            "expectedIntervalMs=${String.format(Locale.US, "%.2f", intervalMs(case))} expectedTotalCs=${case.seconds * 100} " +
            "decodedIndices=${c.decoded} expectedIndices=${plan.map { expectedSourceIndex(case, it) }} " +
            "adjacentDuplicates=$duplicates probeTimesUs=${c.probeTimesUs} closestNulls=${c.closestNulls} " +
            "reportedSyncFallbacks=${c.reportedSyncFallbacks} retrieverFrame=${c.retrieverSize} elapsedMs=${c.elapsedMs}"
    }

    companion object {
        private fun hex(value: Int?): String = value?.let { "%08x".format(it) } ?: "none"

        private const val FIXTURE_DIR = "Pictures/GlimpseGifTest/"
        private const val MAX_FRAMES = 36
        private const val ALPHA = 0xFF shl 24
        private const val ODD_WIDTH = 321
        private const val ODD_HEIGHT = 181
        private val FIXTURE_PALETTE = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)

        private val L30 = VideoSpec("land30_480x270_4s", 480, 270, 30, 120)
        private val L60 = VideoSpec("land60_320x180_5s", 320, 180, 60, 300)
        private val L30_LONG = VideoSpec("land30_320x180_11s", 320, 180, 30, 330)
        private val L60_LONG = VideoSpec("land60_256x144_19s", 256, 144, 60, 1140)
        private val P30_ROT = VideoSpec("portrait30_rot90_270x480_3s", 480, 270, 30, 90, rotation = 90)
        private val L30_HD = VideoSpec("land30_1280x720_2s", 1280, 720, 30, 60)

        private val BASE_CASES = listOf(
            GifCase("short30_3s_10fps_240p", "s30", L30, 3, 10, 240, 240, 135, true),
            GifCase("short60_4s_8fps", "s60", L60, 4, 8, 480, 320, 180, true),
            GifCase("reduced30_10s_15fps", "r30", L30_LONG, 10, 15, 480, 320, 180, true),
            GifCase("default30_5s_8fps", "d30", L30_LONG, 5, 8, 480, 320, 180, true),
            GifCase("reduced60_18s_5fps", "r60", L60_LONG, 18, 5, 480, 256, 144, true),
            GifCase("portrait_rot90_2s_10fps_240p", "rot", P30_ROT, 2, 10, 240, 135, 240, false),
            GifCase("hd30_1s_5fps_720p", "hd", L30_HD, 1, 5, 720, 720, 405, true),
        )

        private val encoderProbe: List<EncoderProbe> by lazy { SyntheticVideo.probeEncoders(ODD_WIDTH, ODD_HEIGHT) }

        private val oddCase: GifCase? by lazy {
            encoderProbe.firstOrNull { it.oddSizeSupported && it.flexibleYuv }?.let { probe ->
                GifCase("odd_source_321x181_1s_10fps", "odd",
                    VideoSpec("odd30_321x181_2s", ODD_WIDTH, ODD_HEIGHT, 30, 60, mime = probe.mime, encoderName = probe.codec),
                    1, 10, 480, ODD_WIDTH, ODD_HEIGHT, true)
            }
        }

        private val cases: List<GifCase> get() = BASE_CASES + listOfNotNull(oddCase)
        private val videoFiles = HashMap<String, File>()
        private val conversions = HashMap<String, Conversion>()
    }
}
