/* Emulator-only screenshot scenarios using synthetic, isolated MediaStore input.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentUris
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.ViewGroup
import android.app.LocaleManager
import android.os.LocaleList
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.SystemClock
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.glimpse.ImageEditorActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.collage.CollageActivity
import org.lineageos.glimpse.collage.CollageView
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.ui.views.AnnotationOverlayView
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UiScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val images = mutableListOf<Uri>()
    private var activity: Activity? = null
    private val output by lazy { File(context.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() } }

    @Before fun createFixtures() {
        val assets = instrumentation.context.assets
        repeat(6) { index ->
            val name = if (index % 2 == 0) "synthetic_portrait.png" else "synthetic_landscape.png"
            val bitmap = assets.open(name).use { BitmapFactory.decodeStream(it) }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ui_fixture_$index.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GlimpseUiTest")
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            images.add(uri)
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            if (index == 1) context.contentResolver.openFileDescriptor(uri, "rw")!!.use {
                ExifInterface(it.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                    saveAttributes()
                }
            }
        }
    }

    @After fun cleanup() {
        activity?.finish()
        images.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent)
        device.waitForIdle()
    }

    private fun editor() {
        start(ImageEditorActivity.createIntent(context, images.first(), "image/jpeg"))
        val deadline = System.currentTimeMillis() + 20000
        while ((activity?.findViewById<CropImageView>(R.id.cropImageView)?.getDisplayBitmap() == null) &&
            System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertNotNull("Editor image did not load", activity?.findViewById<CropImageView>(R.id.cropImageView)?.getDisplayBitmap())
        var markup = device.findObject(By.text(activity!!.getString(R.string.editor_tab_markup)))
        if (markup == null) {
            val tabs = device.findObject(By.res(context.packageName, "categoryTabsScroll"))
            assertNotNull("Category scroll missing", tabs)
            val bounds = tabs!!.visibleBounds
            device.swipe(bounds.right - 15, bounds.centerY(), bounds.left + 15, bounds.centerY(), 25)
            markup = device.wait(Until.findObject(By.text(activity!!.getString(R.string.editor_tab_markup))), 5000)
        }
        assertNotNull("Markup category is not visible", markup)
        markup!!.click()
        assertNotNull("Markup tools unavailable", device.wait(Until.findObject(By.desc(activity!!.getString(R.string.editor_markup_doodle))), 10000))
    }

    private fun tool(id: Int) {
        val name = context.getString(id)
        val selector = By.desc(name)
        var obj = device.findObject(selector)
        if (obj == null) {
            val scroll = device.findObject(By.res(context.packageName, "markupToolsScroll"))
            assertNotNull("Tool scroll unavailable", scroll)
            val r = scroll!!.visibleBounds
            device.swipe(r.right - 15, r.centerY(), r.left + 15, r.centerY(), 25)
            obj = device.wait(Until.findObject(selector), 5000)
        }
        assertNotNull("Missing tool: $name", obj)
        obj!!.click()
        assertTrue("Tool was not selected: $name", device.wait(Until.hasObject(By.desc(name).selected(true)), 10000))
        device.waitForIdle()
    }

    private fun collage(count: Int) {
        start(CollageActivity.createIntent(context, images.take(count)))
        val save = device.wait(Until.findObject(By.res(context.packageName, "collageSave")), 15000)
        assertNotNull("Collage rejected $count photos or did not open", save)
        assertTrue("Collage loading did not complete", device.wait(Until.hasObject(By.res(context.packageName, "collageSave").enabled(true)), 30000))
        // The old baseline uses a deliberately dark-only viewer theme. Assert the
        // actual resolved surface, not merely the emulator's night-mode setting.
        if (context.resources.getIdentifier("collageModePhoto", "id", context.packageName) != 0) {
            val expected = InstrumentationRegistry.getArguments().getString("expectedTheme")
            if (expected != null) instrumentation.runOnMainSync {
                val root = activity!!.findViewById<View>(R.id.collageRoot)
                val color = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
                val light = ColorUtils.calculateLuminance(color) > 0.5
                assertEquals("Resolved collage surface does not match $expected mode", expected == "light", light)
            }
        }
    }

    private fun capture(name: String) {
        // Both the hierarchy and compositor must settle; idleness alone can precede a frame.
        val png = File(output, "$name.png")
        val xml = File(output, "$name.xml")
        val nextPng = File(output, "${name}-probe.png")
        val nextXml = File(output, "${name}-probe.xml")
        var stable = false
        repeat(4) {
            if (!stable) {
                device.waitForIdle()
                Thread.sleep(600)
                device.dumpWindowHierarchy(xml)
                assertTrue("Screenshot failed: $name", device.takeScreenshot(png))
                Thread.sleep(350)
                device.dumpWindowHierarchy(nextXml)
                assertTrue("Screenshot probe failed: $name", device.takeScreenshot(nextPng))
                stable = xml.readBytes().contentEquals(nextXml.readBytes()) &&
                    png.readBytes().contentEquals(nextPng.readBytes())
            }
        }
        nextPng.delete()
        nextXml.delete()
        assertTrue("Screen or hierarchy did not stabilize: $name", stable)
        // App-specific external storage may disappear after instrumentation uninstalls the app.
        // Publish the pair via MediaStore while we still own it, so adb can retrieve it later.
        listOf("png" to "image/png", "xml" to "application/xml").forEach { (extension, mime) ->
            val source = File(output, "$name.$extension")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$name.$extension")
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                source.inputStream().use { input ->
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                        input.copyTo(output)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(context.contentResolver.update(uri, values, null, null) == 1)
            } catch (t: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw t
            }
        }
    }

    private fun isCandidate() = InstrumentationRegistry.getArguments().getString("uiVersion") != "baseline"

    /** Locate foreground pixels in the actual composited screenshot, within a view. */
    private fun screenInkBounds(view: View, screenshot: Bitmap, foreground: Int): Rect {
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        val left = pos[0].coerceAtLeast(0); val top = pos[1].coerceAtLeast(0)
        val right = (pos[0] + view.width).coerceAtMost(screenshot.width)
        val bottom = (pos[1] + view.height).coerceAtMost(screenshot.height)
        var minX = right; var minY = bottom; var maxX = -1; var maxY = -1
        for (y in top until bottom) for (x in left until right) {
            val pixel = screenshot.getPixel(x, y)
            val distance = kotlin.math.abs(Color.red(pixel) - Color.red(foreground)) +
                kotlin.math.abs(Color.green(pixel) - Color.green(foreground)) +
                kotlin.math.abs(Color.blue(pixel) - Color.blue(foreground))
            if (distance < 100) {
                minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
        }
        assertTrue("No foreground pixels for ${view.javaClass.simpleName}", maxX >= minX)
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun assertMarkupAlignment() {
        if (!isCandidate()) return
        val row = activity!!.findViewById<LinearLayout>(R.id.markupToolsRow)
        val density = activity!!.resources.displayMetrics.density
        device.waitForIdle()
        Thread.sleep(350)
        val initial = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.runOnMainSync {
                assertEquals(5, row.childCount)
                for (index in 0 until 5) {
                    val item = row.getChildAt(index) as LinearLayout
                    val icon = (item.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView
                    val label = item.getChildAt(1) as TextView
                    val iconCenter = screenInkBounds(icon, initial, icon.imageTintList!!.defaultColor).exactCenterX()
                    val textCenter = screenInkBounds(label, initial, label.currentTextColor).exactCenterX()
                    assertTrue("Tool $index icon/text ink centers differ: $iconCenter vs $textCenter",
                        kotlin.math.abs(iconCenter - textCenter) <= 3 * density)
                }
            }
        } finally { initial.recycle() }
        var width = -1
        for (index in 0 until 5) {
            instrumentation.runOnMainSync { row.getChildAt(index).performClick() }
            device.waitForIdle()
            Thread.sleep(350)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                instrumentation.runOnMainSync {
                    val selectedItem = row.getChildAt(index) as LinearLayout
                    assertTrue("Tool $index not selected", selectedItem.isSelected)
                    if (width < 0) width = selectedItem.width
                    for (i in 0 until row.childCount) {
                        val item = row.getChildAt(i) as LinearLayout
                        assertEquals("Tool width shifted with selection", width, item.width)
                        val label = item.getChildAt(0) as TextView
                        val itemPos = IntArray(2)
                        item.getLocationOnScreen(itemPos)
                        val textCenter = screenInkBounds(label, screenshot, label.currentTextColor).exactCenterX()
                        assertTrue("Compact tool $i ink is off-center", kotlin.math.abs(textCenter - (itemPos[0] + item.width / 2f)) <= 3 * density)
                    }
                }
            } finally { screenshot.recycle() }
        }
    }

    @Test fun markupIdle() { editor(); capture("markup-idle"); assertMarkupAlignment() }
    @Test fun markupIdleChinese() {
        try {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-CN")
            device.waitForIdle()
            editor()
            assertEquals("涂鸦", activity!!.getString(R.string.editor_markup_doodle))
            capture("markup-idle-zh")
            assertMarkupAlignment()
        } finally {
            activity?.finish()
            activity = null
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
            device.waitForIdle()
        }
    }
    @Test fun markupShapes() { editor(); tool(R.string.editor_markup_shapes); capture("markup-shapes") }
    @Test fun markupDensity() {
        try {
            device.executeShellCommand("wm density 320")
            device.waitForIdle()
            Thread.sleep(1000)
            editor(); tool(R.string.editor_markup_doodle)
            instrumentation.runOnMainSync { assertEquals("Density override not applied", 320, activity!!.resources.displayMetrics.densityDpi) }
            if (isCandidate()) instrumentation.runOnMainSync {
                val root = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
                fun descendants(group: ViewGroup): List<View> = (0 until group.childCount).flatMap { i ->
                    val child = group.getChildAt(i)
                    listOf(child) + if (child is ViewGroup) descendants(child) else emptyList()
                }
                val views = descendants(root)
                val importButton = views.filterIsInstance<TextView>().first { it.text == activity!!.getString(R.string.editor_brush_import) }
                val red = views.first { it.contentDescription == activity!!.getString(R.string.markup_color_red) }
                val density = activity!!.resources.displayMetrics.density
                assertEquals((14 * density).toInt(), importButton.paddingLeft)
                assertEquals((8 * density).toInt(), (red.layoutParams as LinearLayout.LayoutParams).marginEnd)
            }
            capture("markup-density")
        } finally {
            activity?.finish()
            activity = null
            device.executeShellCommand("wm density reset")
            device.waitForIdle()
        }
    }

    private fun assertSingleOptionLabel(label: Int) {
        if (!isCandidate()) return
        instrumentation.runOnMainSync {
            val text = activity!!.getString(label)
            fun count(view: View): Int {
                val self = if (view is TextView && view.text.toString().startsWith(text)) 1 else 0
                return self + if (view is ViewGroup) (0 until view.childCount).sumOf { count(view.getChildAt(it)) } else 0
            }
            assertEquals("Repeated option label $text", 1, count(activity!!.findViewById(R.id.markupOptionsContent)))
        }
    }

    @Test fun markupBrush() {
        editor(); tool(R.string.editor_markup_doodle); assertSingleOptionLabel(R.string.editor_markup_stroke_width); capture("markup-brush")
        val scroll = device.findObject(By.res(context.packageName, "markupOptionsScroll"))
        assertNotNull("Markup options missing", scroll)
        val bounds = scroll!!.visibleBounds
        device.swipe(bounds.centerX(), bounds.bottom - 10, bounds.centerX(), bounds.top + 10, 25)
        assertNotNull("Brush colors not reachable", device.wait(Until.findObject(By.desc(context.getString(R.string.markup_color_black))), 5000))
        capture("markup-brush-scroll")
    }
    @Test fun markupBrushSmall() {
        try {
            device.executeShellCommand("wm size 720x800")
            device.waitForIdle()
            Thread.sleep(1000)
            editor(); tool(R.string.editor_markup_doodle)
            var canScroll = false
            instrumentation.runOnMainSync {
                canScroll = activity!!.findViewById<View>(R.id.markupOptionsScroll).canScrollVertically(1)
            }
            capture("markup-brush-small")
            val scroll = device.findObject(By.res(context.packageName, "markupOptionsScroll"))!!
            val bounds = scroll.visibleBounds
            if (canScroll) device.swipe(bounds.centerX(), bounds.bottom - 10, bounds.centerX(), bounds.top + 10, 25)
            assertNotNull("Small brush panel did not expose colors", device.wait(Until.findObject(By.desc(context.getString(R.string.markup_color_black))), 5000))
            capture("markup-brush-small-scroll")
        } finally {
            activity?.finish()
            device.executeShellCommand("wm size 720x1520")
            device.waitForIdle()
        }
    }
    @Test fun markupMosaic() { editor(); tool(R.string.editor_markup_mosaic); assertSingleOptionLabel(R.string.editor_markup_mosaic_brush); capture("markup-mosaic") }
    @Test fun markupSticker() { editor(); tool(R.string.editor_markup_sticker); capture("markup-sticker") }
    @Test fun markupTextSelected() {
        editor(); tool(R.string.editor_markup_text)
        val overlay = device.findObject(By.res(context.packageName, "annotationOverlayView"))
        assertNotNull("Annotation canvas missing", overlay)
        overlay!!.click()
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
        assertNotNull("Text input not opened", input)
        input!!.text = "Sample text"
        device.findObject(By.text("OK"))?.click() ?: error("Text confirmation unavailable")
        assertTrue("Text dialog did not close", device.wait(Until.gone(By.clazz("android.widget.EditText")), 10000))
        instrumentation.runOnMainSync {
            val overlayView = activity?.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
            assertTrue("Text layer was not selected", overlayView?.activeText?.text == "Sample text")
        }
        capture("markup-text-selected")
        val black = device.wait(Until.findObject(By.desc(context.getString(R.string.markup_color_black))), 5000)
        assertNotNull("Black swatch unavailable", black)
        black!!.click()
        assertTrue("Black swatch not selected", device.wait(Until.hasObject(By.desc(context.getString(R.string.markup_color_black)).selected(true)), 5000))
        instrumentation.runOnMainSync {
            val overlayView = activity?.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
            assertTrue("Black was not applied to the selected text", overlayView?.activeText?.color == Color.BLACK)
        }
        capture("markup-text-black")
    }
    @Test fun collageTwo() { collage(2); capture("collage-two") }
    @Test fun collageSelected() {
        collage(2)
        val preview = device.findObject(By.res(context.packageName, "collagePreview"))
        assertNotNull("Collage preview missing", preview)
        val r = preview!!.visibleBounds
        device.click(r.right - r.width() / 4, r.centerY())
        assertTrue("Second collage photo was not selected", device.wait(
            Until.hasObject(By.textContains("Photo 2 of 2 selected")), 10000))
        capture("collage-selected")
    }
    @Test fun collageFour() { collage(4); capture("collage-four") }
    @Test fun collageFourLayouts() {
        collage(4)
        val toggle = context.resources.getIdentifier("collageModeLayout", "id", context.packageName)
        if (toggle != 0) instrumentation.runOnMainSync { activity!!.findViewById<View>(toggle).performClick() }
        device.waitForIdle()
        capture("collage-four-layouts")
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        instrumentation.runOnMainSync {
            val cards = activity!!.findViewById<LinearLayout>(R.id.collageLayoutCards)
            val before = view.photos.map { it.uri }
            cards.getChildAt(1).performClick()
            assertEquals(1, view.layout)
            assertEquals(before, view.photos.map { it.uri })
        }
    }
    @Test fun collageSix() { collage(6); capture("collage-six") }
    @Test fun collageDragAndZoom() {
        collage(3)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        val strip = activity!!.findViewById<RecyclerView>(R.id.collageFilmstrip)
        device.waitForIdle()
        val positions = IntArray(4)
        instrumentation.runOnMainSync {
            val first = requireNotNull(strip.layoutManager!!.findViewByPosition(0))
            val second = requireNotNull(strip.layoutManager!!.findViewByPosition(1))
            val a = IntArray(2); val b = IntArray(2)
            first.getLocationOnScreen(a); second.getLocationOnScreen(b)
            positions[0] = a[0] + first.width / 2; positions[1] = a[1] + first.height / 2
            positions[2] = b[0] + second.width * 3 / 4; positions[3] = b[1] + second.height / 2
        }
        // Inject an explicit long press: a swipe-like drag may not wait for the
        // ItemTouchHelper long-press timeout on every UiAutomator version.
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue("Touch injection failed", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, positions[0].toFloat(), positions[1].toFloat())
        Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 500)
        for (step in 1..40) {
            val fraction = step / 40f
            touch(MotionEvent.ACTION_MOVE, positions[0] + (positions[2] - positions[0]) * fraction,
                positions[1] + (positions[3] - positions[1]) * fraction)
            Thread.sleep(10)
        }
        touch(MotionEvent.ACTION_UP, positions[2].toFloat(), positions[3].toFloat())
        device.waitForIdle()
        val bounds = Rect()
        instrumentation.runOnMainSync {
            assertEquals(listOf(images[1], images[0], images[2]), view.photos.map { it.uri })
            assertEquals(1, view.selected)
            val slider = activity!!.findViewById<Slider>(R.id.collageZoom)
            var parent = slider.parent
            while (parent != null && parent !is ScrollView) parent = parent.parent
            val scroll = requireNotNull(parent as? ScrollView)
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            assertTrue("Zoom slider not visible", slider.getGlobalVisibleRect(bounds))
            assertTrue("Zoom slider partially clipped", bounds.height() >= slider.height)
        }
        device.waitForIdle()
        device.swipe(bounds.left + bounds.width() / 3, bounds.centerY(), bounds.right - 24, bounds.centerY(), 30)
        instrumentation.runOnMainSync {
            assertTrue("Slider gesture did not zoom selected photo", view.photos[1].zoom > 1f)
            assertEquals(1f, view.photos[0].zoom, 0.001f)
        }
    }

    @Test fun collageReplaceAndCancel() {
        // The baseline in the screenshot workflow predates the replace control.
        val id = context.resources.getIdentifier("collageReplace", "id", context.packageName)
        assumeTrue("Replacement unavailable in baseline", id != 0)
        collage(2)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        fun deliver(result: Instrumentation.ActivityResult) {
            val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("image/*")
            }
            val monitor = instrumentation.addMonitor(filter, result, true)
            instrumentation.runOnMainSync { activity!!.findViewById<View>(id).performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
        }
        deliver(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        instrumentation.runOnMainSync { assertEquals(images.take(2), view.photos.map { it.uri }) }
        deliver(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(images[2])))
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = view.photos.firstOrNull()?.uri == images[2] }
            if (done) break
            Thread.sleep(100)
        }
        instrumentation.runOnMainSync {
            assertEquals(listOf(images[2], images[1]), view.photos.map { it.uri })
            assertEquals(0, view.layout)
        }
    }

    @Test fun collageSaveCreatesNewImage() {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun outputs(): Set<Uri> {
            val result = mutableSetOf<Uri>()
            context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("Pictures/Glimpse/", "Glimpse_Collage_%"), null)?.use { cursor ->
                while (cursor.moveToNext()) result.add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
            return result
        }
        fun digest(uri: Uri): List<Byte> = context.contentResolver.openInputStream(uri)!!.use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        val before = outputs()
        val originalHashes = images.take(2).map(::digest)
        try {
            collage(2)
            instrumentation.runOnMainSync { activity!!.findViewById<View>(R.id.collageSave).performClick() }
            val deadline = System.currentTimeMillis() + 30000
            while (outputs().subtract(before).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(200)
            val created = outputs().subtract(before)
            assertEquals("Expected exactly one new image", 1, created.size)
            val saved = created.single()
            val bitmap = context.contentResolver.openInputStream(saved)!!.use { BitmapFactory.decodeStream(it) }
            assertNotNull("Export is not a readable image", bitmap)
            assertEquals(1800, bitmap.width)
            assertEquals(1800, bitmap.height)
            bitmap.recycle()
            assertEquals("Originals must not be overwritten", originalHashes, images.take(2).map(::digest))
        } finally {
            outputs().subtract(before).forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun collageSmall() {
        try {
            device.executeShellCommand("wm size 360x720")
            device.waitForIdle()
            Thread.sleep(1000)
            collage(2)
            if (isCandidate()) instrumentation.runOnMainSync {
                val root = activity!!.findViewById<View>(R.id.collageRoot)
                val panelId = context.resources.getIdentifier("collagePanelHost", "id", context.packageName)
                val panel = activity!!.findViewById<View>(panelId)
                assertTrue("Tool panel takes too much of a short window", panel.height <= root.height * 0.38f + 1)
                assertTrue("Preview has no usable space", activity!!.findViewById<View>(R.id.collagePreview).height > 0)
            }
            capture("collage-small")
        } finally {
            activity?.finish()
            activity = null
            device.executeShellCommand("wm size 720x1520")
            device.waitForIdle()
        }
    }

    @Test fun collageLandscape() {
        try {
            device.setOrientationLeft()
            device.waitForIdle()
            collage(2); capture("collage-landscape")
        } finally {
            activity?.finish()
            device.setOrientationNatural()
            device.unfreezeRotation()
            device.waitForIdle()
        }
    }
}
