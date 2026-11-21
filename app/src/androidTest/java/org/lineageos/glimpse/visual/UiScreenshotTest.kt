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
import org.junit.Assert.assertNull
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
import org.lineageos.glimpse.ui.views.MosaicEffect
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

    // Candidate-only: the fixed dock is not present in the historical baseline.
    private fun subtool(label: Int) {
        if (!isCandidate()) return
        val row = device.wait(Until.findObject(By.res(context.packageName, "markupSubtoolsRow")), 5000)
        assertNotNull("Subtool dock missing", row)
        var item = row!!.findObject(By.desc(context.getString(label)))
        if (item == null) {
            val scroll = device.findObject(By.res(context.packageName, "markupSubtoolsScroll"))
            assertNotNull("Subtool scroll missing", scroll)
            val bounds = scroll!!.visibleBounds
            device.swipe(bounds.right - 5, bounds.centerY(), bounds.left + 5, bounds.centerY(), 20)
            item = device.findObject(By.desc(context.getString(label)))
        }
        if (item == null) {
            val scroll = device.findObject(By.res(context.packageName, "markupSubtoolsScroll"))!!
            val bounds = scroll.visibleBounds
            device.swipe(bounds.left + 5, bounds.centerY(), bounds.right - 5, bounds.centerY(), 20)
            item = device.findObject(By.desc(context.getString(label)))
        }
        assertNotNull("Subtool missing: ${context.getString(label)}", item)
        item!!.click()
        device.waitForIdle()
        val selected = device.findObject(By.res(context.packageName, "markupSubtoolsRow"))
            ?.findObject(By.desc(context.getString(label)))
        assertTrue("Subtool not selected", selected?.isSelected == true)
    }

    private fun collage(count: Int) {
        start(CollageActivity.createIntent(context, images.take(count)))
        val save = device.wait(Until.findObject(By.res(context.packageName, "collageSave")), 15000)
        assertNotNull("Collage rejected $count photos or did not open", save)
        assertTrue("Collage loading did not complete", device.wait(Until.hasObject(By.res(context.packageName, "collageSave").enabled(true)), 30000))
        // The old baseline uses a deliberately dark-only viewer theme. Assert the
        // actual resolved surface, not merely the emulator's night-mode setting.
        if (isCandidate() || context.resources.getIdentifier("collageModePhoto", "id", context.packageName) != 0) {
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
        device.waitForIdle()
        Thread.sleep(600)
        device.dumpWindowHierarchy(xml)
        assertTrue("Screenshot failed: $name", device.takeScreenshot(png))
        repeat(4) { attempt ->
            if (!stable) {
                if (attempt > 0) {
                    // The preceding probe is the first sample of the next pair.
                    // Keep the existing idle/settle delay before the new probe.
                    nextXml.copyTo(xml, overwrite = true)
                    nextPng.copyTo(png, overwrite = true)
                    device.waitForIdle()
                    Thread.sleep(600)
                }
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

    @Test fun markupNonMarkupCategories() {
        assumeTrue("Candidate navigation evidence", isCandidate())
        start(ImageEditorActivity.createIntent(context, images.first(), "image/jpeg"))
        assertTrue("Editor categories not shown", activity!!.findViewById<View>(R.id.categoryTabsScroll).visibility == View.VISIBLE)
        capture("markup-dock-non-markup")
    }

    @Test fun markupMatchesEditorColorsAndCentersOptions() {
        assumeTrue("Candidate editor styling", isCandidate())
        editor()
        instrumentation.runOnMainSync {
            val deck = activity!!.findViewById<View>(R.id.editorBottomDeck)
            assertEquals("Markup deck should match the black editor", Color.BLACK,
                (deck.background as android.graphics.drawable.ColorDrawable).color)
            val tabs = activity!!.findViewById<ViewGroup>(R.id.categoryTabsContainer)
            val selected = (0 until tabs.childCount).map { tabs.getChildAt(it) }
                .filterIsInstance<TextView>().first { it.text == activity!!.getString(R.string.editor_tab_markup) }
            val expected = context.getDrawable(R.drawable.bg_google_photos_pill_selected) as android.graphics.drawable.GradientDrawable
            assertEquals("Markup category uses a different selected color", expected.color!!.defaultColor,
                (selected.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
            assertEquals(Color.WHITE, selected.currentTextColor)
        }
        tool(R.string.editor_markup_doodle)
        instrumentation.runOnMainSync {
            val deck = activity!!.findViewById<View>(R.id.editorBottomDeck)
            assertEquals(Color.BLACK, (deck.background as android.graphics.drawable.ColorDrawable).color)
            val options = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
            assertEquals(Color.BLACK, (options.background as android.graphics.drawable.ColorDrawable).color)
            val section = options.getChildAt(0)
            val top = section.top
            val bottom = options.height - section.bottom
            assertTrue("Option row is not vertically centered", kotlin.math.abs(top - bottom) <= (2 * context.resources.displayMetrics.density).toInt())
            val row = activity!!.findViewById<ViewGroup>(R.id.markupToolsRow)
            val active = (0 until row.childCount).map { row.getChildAt(it) }.first { it.isSelected }
            assertEquals("Selected tool should match editor tabs", Color.parseColor("#383838"),
                (active.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
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
                val density = activity!!.resources.displayMetrics.density
                assertEquals((14 * density).toInt(), importButton.paddingLeft)
            }
            if (isCandidate()) {
                subtool(R.string.markup_color_section)
                instrumentation.runOnMainSync {
                    val root = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
                    fun find(group: ViewGroup): View? {
                        for (i in 0 until group.childCount) {
                            val child = group.getChildAt(i)
                            if (child.contentDescription == activity!!.getString(R.string.markup_color_red)) return child
                            if (child is ViewGroup) find(child)?.let { return it }
                        }
                        return null
                    }
                    val red = requireNotNull(find(root))
                    assertEquals((2 * activity!!.resources.displayMetrics.density).toInt(), (red.layoutParams as LinearLayout.LayoutParams).marginEnd)
                }
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
        editor(); tool(R.string.editor_markup_doodle)
        subtool(R.string.editor_markup_stroke_width)
        assertSingleOptionLabel(R.string.editor_markup_stroke_width)
        subtool(R.string.markup_color_section)
        if (isCandidate()) instrumentation.runOnMainSync {
            val root = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
            fun find(group: ViewGroup): View? {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child.contentDescription == activity!!.getString(R.string.markup_color_red)) return child
                    if (child is ViewGroup) find(child)?.let { return it }
                }
                return null
            }
            val red = requireNotNull(find(root))
            val bounds = Rect()
            assertTrue("Initial swatch should be fully visible", red.getGlobalVisibleRect(bounds) && bounds.height() == red.height)
        }
        capture("markup-brush")
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
            subtool(R.string.markup_color_section)
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
    @Test fun markupMosaic() {
        editor(); tool(R.string.editor_markup_mosaic)
        subtool(R.string.editor_markup_mosaic_brush)
        assertSingleOptionLabel(R.string.editor_markup_mosaic_brush)
        capture("markup-mosaic")
    }
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
        subtool(R.string.markup_color_section)
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
    @Test fun markupFixedSubtoolDockPreservesFrameAndSelection() {
        assumeTrue("Subtool dock is candidate-only", isCandidate())
        editor()
        capture("markup-dock-categories")
        instrumentation.runOnMainSync {
            assertEquals("Category bar missing before sub-edit", View.VISIBLE, activity!!.findViewById<View>(R.id.categoryTabsScroll).visibility)
        }
        tool(R.string.editor_markup_text)
        instrumentation.runOnMainSync {
            assertEquals("Category bar visible during Markup", View.GONE, activity!!.findViewById<View>(R.id.categoryTabsScroll).visibility)
            assertEquals("Markup main tools missing", View.VISIBLE, activity!!.findViewById<View>(R.id.markupToolsScroll).visibility)
        }
        val crop = activity!!.findViewById<CropImageView>(R.id.cropImageView)
        val overlay = activity!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
        var originalText: org.lineageos.glimpse.ui.views.AnnotationItem.Text? = null
        var originalMatrix = FloatArray(9)
        var originalWidth = 0
        var originalHeight = 0
        var originalOptionsHeight = 0
        instrumentation.runOnMainSync {
            overlay.addTextItem("Dock test", crop.width / 2f, crop.height / 2f)
            originalText = overlay.activeText
            crop.getImageMatrix().getValues(originalMatrix)
            originalWidth = crop.width; originalHeight = crop.height
            originalOptionsHeight = activity!!.findViewById<View>(R.id.markupOptionsScroll).height
            assertTrue("Options viewport is not compact", originalOptionsHeight <= (96 * activity!!.resources.displayMetrics.density).toInt())
        }
        fun assertPageFrameStable() {
            instrumentation.runOnMainSync {
                val matrix = FloatArray(9)
                crop.getImageMatrix().getValues(matrix)
                assertEquals("Options height changed", originalOptionsHeight, activity!!.findViewById<View>(R.id.markupOptionsScroll).height)
                assertEquals("Preview height changed", originalHeight, crop.height)
                for (i in matrix.indices) assertEquals("Image matrix changed at $i", originalMatrix[i], matrix[i], .001f)
                assertTrue("Selection lost on page switch", overlay.activeText === originalText)
            }
        }
        capture("markup-dock-text-edit")
        subtool(R.string.markup_color_section); assertPageFrameStable(); capture("markup-dock-text-color")
        subtool(R.string.editor_markup_text_bg); assertPageFrameStable(); capture("markup-dock-text-background")
        val tools = listOf(
            R.string.editor_markup_doodle to "markup-dock-brush",
            R.string.editor_markup_shapes to "markup-dock-shapes",
            R.string.editor_markup_mosaic to "markup-dock-mosaic-effect",
            R.string.editor_markup_sticker to "markup-dock-sticker",
            R.string.editor_markup_text to "markup-dock-text-restored",
        )
        for ((toolName, screenshot) in tools) {
            tool(toolName)
            capture(screenshot)
            instrumentation.runOnMainSync {
                val matrix = FloatArray(9)
                crop.getImageMatrix().getValues(matrix)
                assertEquals("Preview width changed", originalWidth, crop.width)
                assertEquals("Preview height changed", originalHeight, crop.height)
                assertEquals("Options height changed", originalOptionsHeight, activity!!.findViewById<View>(R.id.markupOptionsScroll).height)
                for (i in matrix.indices) assertEquals("Image matrix changed at $i", originalMatrix[i], matrix[i], .001f)
                assertTrue("Selected text was lost on tool switch", overlay.activeText === originalText)
                assertTrue("Annotation lost", overlay.hasAnnotations())
                val dockId = context.resources.getIdentifier("markupSubtoolsRow", "id", context.packageName)
                val dock = activity!!.findViewById<ViewGroup>(dockId)
                assertTrue("Subtool pills missing", dock.childCount >= 2)
                for (i in 0 until dock.childCount) {
                    val pill = dock.getChildAt(i)
                    assertTrue("Subtool must be icon-only", pill is ImageView)
                    assertTrue("Subtool must have an accessible name", !pill.contentDescription.isNullOrBlank())
                }
            }
            if (toolName == R.string.editor_markup_mosaic) {
                subtool(R.string.editor_markup_mosaic_brush); capture("markup-dock-mosaic-width")
                subtool(R.string.markup_effect_section)
                assertNotNull("Effect entry disappeared", device.findObject(By.text(context.getString(R.string.editor_mosaic_pixelate))))
            }
        }
        val clearLabel = context.resources.getIdentifier("markup_clear_annotations", "string", context.packageName)
        assertTrue("Clear accessibility label missing", clearLabel != 0)
        val clear = device.findObject(By.desc(context.getString(clearLabel)))
        assertNotNull("Clear button missing", clear)
        clear!!.click(); device.waitForIdle()
        instrumentation.runOnMainSync { assertTrue("Clear left annotations", !overlay.hasAnnotations()) }
        val undo = device.findObject(By.desc(context.getString(R.string.editor_markup_undo)))
        assertNotNull("Undo button missing", undo)
        undo!!.click(); device.waitForIdle()
        instrumentation.runOnMainSync { assertTrue("Undo did not restore cleared annotation", overlay.hasAnnotations()) }
        capture("markup-dock-undo")
        val redo = device.findObject(By.desc(context.getString(R.string.editor_redo)))
        assertNotNull("Redo button missing", redo)
        redo!!.click(); device.waitForIdle()
        instrumentation.runOnMainSync { assertTrue("Redo did not clear annotations", !overlay.hasAnnotations()) }
        capture("markup-dock-redo")
        undo.click(); device.waitForIdle()
        device.findObject(By.res(context.packageName, "subEditCancelButton"))!!.click(); device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals("Category bar not restored after close", View.VISIBLE, activity!!.findViewById<View>(R.id.categoryTabsScroll).visibility)
            assertTrue("Close lost annotation", overlay.hasAnnotations())
        }
        capture("markup-dock-close")
        tool(R.string.editor_markup_text)
        device.findObject(By.res(context.packageName, "subEditDoneButton"))!!.click(); device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals("Category bar not restored after done", View.VISIBLE, activity!!.findViewById<View>(R.id.categoryTabsScroll).visibility)
            assertTrue("Done lost annotation", overlay.hasAnnotations())
        }
        capture("markup-dock-done")
    }

    @Test fun markupDockNarrowAndLargeFont() {
        assumeTrue("Subtool dock is candidate-only", isCandidate())
        val previousScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("wm size 720x1520")
            device.executeShellCommand("wm density 320")
            device.executeShellCommand("settings put system font_scale 1.3")
            device.waitForIdle()
            Thread.sleep(1000)
            editor(); tool(R.string.editor_markup_shapes)
            capture("markup-dock-narrow-large-font")
            instrumentation.runOnMainSync {
                val scrollId = context.resources.getIdentifier("markupSubtoolsScroll", "id", context.packageName)
                assertTrue("Subtool scroller missing", scrollId != 0)
                val scroll = activity!!.findViewById<View>(scrollId)
                val cancel = activity!!.findViewById<View>(R.id.subEditCancelButton)
                val done = activity!!.findViewById<View>(R.id.subEditDoneButton)
                val nav = androidx.core.view.ViewCompat.getRootWindowInsets(scroll)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                val scrollRect = Rect(); val doneRect = Rect(); val cancelRect = Rect()
                assertTrue("Dock not visible", scroll.getGlobalVisibleRect(scrollRect))
                assertTrue("Close clipped", cancel.getGlobalVisibleRect(cancelRect) && cancelRect.width() == cancel.width)
                assertTrue("Done clipped", done.getGlobalVisibleRect(doneRect) && doneRect.width() == done.width)
                assertTrue("Subtool viewport too narrow", scrollRect.width() >= (48 * activity!!.resources.displayMetrics.density).toInt())
                assertTrue("Dock overlaps navigation bar", scrollRect.bottom <= scroll.rootView.height - nav)
            }
            subtool(R.string.markup_color_section)
        } finally {
            activity?.finish(); activity = null
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
            if (previousScale == "null" || previousScale.isEmpty()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $previousScale")
            device.waitForIdle()
        }
    }

    @Test fun markupEditorToolStyleAndMaterialSaveDialog() {
        assumeTrue("Editor style evidence is candidate-only", isCandidate())
        editor()
        val markup = activity!!.findViewById<LinearLayout>(R.id.markupToolsRow)
        var markupColor = 0; var markupTint = 0; var markupLabelColor = 0
        instrumentation.runOnMainSync {
            val item = markup.getChildAt(0) as LinearLayout
            val circle = item.getChildAt(0) as FrameLayout
            markupColor = (circle.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
            markupTint = (circle.getChildAt(0) as ImageView).imageTintList!!.defaultColor
            markupLabelColor = (item.getChildAt(1) as TextView).currentTextColor
        }
        capture("editor-markup-style")
        var adjust = device.findObject(By.text(context.getString(R.string.editor_tab_adjust)))
        if (adjust == null) {
            val tabs = device.findObject(By.res(context.packageName, "categoryTabsScroll"))!!
            val r = tabs.visibleBounds
            device.swipe(r.left + 15, r.centerY(), r.right - 15, r.centerY(), 25)
            adjust = device.wait(Until.findObject(By.text(context.getString(R.string.editor_tab_adjust))), 5000)
        }
        assertNotNull("Adjust category missing", adjust)
        adjust!!.click(); device.waitForIdle()
        instrumentation.runOnMainSync {
            val items = activity!!.findViewById<LinearLayout>(R.id.adjustToolItemsLayout)
            val item = items.getChildAt(0) as LinearLayout
            val circle = item.getChildAt(0) as FrameLayout
            assertEquals("Tool circle colors differ", markupColor,
                (circle.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
            assertEquals("Tool icon tints differ", markupTint, (circle.getChildAt(0) as ImageView).imageTintList!!.defaultColor)
            assertEquals("Tool captions differ", markupLabelColor, (item.getChildAt(1) as TextView).currentTextColor)
        }
        capture("editor-adjust-style")
        instrumentation.runOnMainSync {
            val method = ImageEditorActivity::class.java.getDeclaredMethod("askAndSave", ByteArray::class.java)
            method.isAccessible = true
            method.invoke(activity, ByteArray(0))
        }
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.editor_save_dialog_title))), 5000))
        assertNotNull(device.findObject(By.text(context.getString(R.string.editor_save_new))))
        assertNotNull(device.findObject(By.text(context.getString(R.string.editor_save_overwrite))))
        capture("editor-save-dialog-md3")
        device.pressBack()
    }

    @Test fun markupMosaicChoices() {
        assumeTrue("Flat effect selector only applies to the candidate", isCandidate())
        editor(); tool(R.string.editor_markup_mosaic)
        val effects = listOf(
            R.string.editor_mosaic_pixelate to MosaicEffect.PIXELATE,
            R.string.editor_mosaic_blur to MosaicEffect.BLUR,
            R.string.editor_mosaic_blackout to MosaicEffect.BLACKOUT,
            R.string.editor_mosaic_stripes to MosaicEffect.STRIPES,
            R.string.editor_mosaic_noise to MosaicEffect.NOISE,
        )
        for ((label, effect) in effects) instrumentation.runOnMainSync {
            val root = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
            fun find(group: ViewGroup): TextView? {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is TextView && child.text == activity!!.getString(label)) return child
                    if (child is ViewGroup) find(child)?.let { return it }
                }
                return null
            }
            requireNotNull(find(root)) { "Missing mosaic effect $label" }.performClick()
            assertEquals(effect, activity!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView).activeMosaicEffect)
        }
    }
    @Test fun chineseToolPanelsAndCollage() {
        try {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-CN")
            device.waitForIdle()
            editor()
            listOf(R.string.editor_markup_doodle to "markup-brush-zh",
                R.string.editor_markup_shapes to "markup-shapes-zh",
                R.string.editor_markup_text to "markup-text-zh",
                R.string.editor_markup_mosaic to "markup-mosaic-zh",
                R.string.editor_markup_sticker to "markup-sticker-zh").forEach { (id, name) ->
                tool(id); capture(name)
            }
            activity?.finish(); activity = null
            collage(4); capture("collage-four-zh")
            instrumentation.runOnMainSync {
                val toggle = context.resources.getIdentifier("collageModeLayout", "id", context.packageName)
                if (toggle != 0) activity!!.findViewById<View>(toggle)?.performClick()
            }
            device.waitForIdle(); capture("collage-four-layouts-zh")
        } finally {
            activity?.finish(); activity = null
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
            device.waitForIdle()
        }
    }
    private fun collageLayouts(count: Int, name: String) {
        collage(count)
        instrumentation.runOnMainSync {
                val toggle = context.resources.getIdentifier("collageModeLayout", "id", context.packageName)
                if (toggle != 0) activity!!.findViewById<View>(toggle)?.performClick()
            }
        device.waitForIdle(); capture(name)
    }
    @Test fun collageTwoLayouts() { collageLayouts(2, "collage-two-layouts") }
    @Test fun collageSixLayouts() { collageLayouts(6, "collage-six-layouts") }

    @Test fun markupCustomShapeImportDrawAndDelete() = exerciseCustomShapeImport(bypassSaf = false)

    // Bypass the system picker only in this diagnostic; the other test still exercises it.
    @Test fun markupCustomShapeImportWithResultIntent() = exerciseCustomShapeImport(bypassSaf = true)

    private fun exerciseCustomShapeImport(bypassSaf: Boolean) {
        assumeTrue("Custom shape UI is candidate-only", isCandidate())
        val mode = if (bypassSaf) "intent" else "saf"
        val manager = org.lineageos.glimpse.models.CustomEditorAssetManager
        val original = manager.getCustomShapes(context)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "arena_custom_shape.svg")
            put(MediaStore.Downloads.MIME_TYPE, "image/svg+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                it.write("<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M 0 0 L 100 0 L 50 100 Z\"/></svg>".toByteArray())
            }
            editor(); tool(R.string.editor_markup_shapes); subtool(R.string.markup_shape_section)
            var button = device.findObject(By.text(context.getString(R.string.editor_shape_import)))
            if (button == null) {
                val outer = requireNotNull(device.findObject(By.res(context.packageName, "markupOptionsScroll")))
                val row = requireNotNull(outer.findObject(By.clazz("android.widget.HorizontalScrollView")))
                val r = row.visibleBounds
                capture("markup-shape-import-$mode-before-scroll")
                repeat(3) { assertTrue("Shape options swipe injection failed", device.swipe(r.right - 10, r.centerY(), r.left + 10, r.centerY(), 20)) }
                device.waitForIdle()
                capture("markup-shape-import-$mode-after-scroll")
                button = device.findObject(By.text(context.getString(R.string.editor_shape_import)))
            }
            assertNotNull("Custom shape import action missing", button)
            if (bypassSaf) {
                // Match the full GetContent intent, including its MIME type and category.
                // The result intent supplies only our app-owned synthetic URI.
                val filter = IntentFilter(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addDataType("*/*")
                }
                val result = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
                try {
                    button!!.click()
                    device.waitForIdle()
                    assertTrue("GetContent intent was not intercepted; SAF bypass not exercised", monitor.hits > 0)
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            } else {
                button!!.click()
                val file = device.wait(Until.findObject(By.text("arena_custom_shape.svg")), 10000)
                if (file == null) capture("markup-shape-import-$mode-picker-missing")
                assertNotNull("Synthetic SVG missing from system file picker", file)
                file!!.click()
            }
            assertTrue("Editor did not resume after file selection", device.wait(
                Until.hasObject(By.res(context.packageName, "markupOptionsScroll")), 10000))
            var shown = device.wait(Until.hasObject(By.text("arena_custom_shape")), 3000)
            if (!shown) {
                val outer = requireNotNull(device.findObject(By.res(context.packageName, "markupOptionsScroll")))
                val row = requireNotNull(outer.findObject(By.clazz("android.widget.HorizontalScrollView")))
                val bounds = row.visibleBounds
                repeat(3) {
                    if (!shown) {
                        assertTrue("Shape options swipe injection failed", device.swipe(bounds.right - 10, bounds.centerY(), bounds.left + 10, bounds.centerY(), 20))
                        shown = device.wait(Until.hasObject(By.text("arena_custom_shape")), 1000)
                    }
                }
            }
            if (!shown) capture("markup-shape-import-$mode-result")
            val stored = manager.getCustomShapes(context).map { it.name }
            assertTrue("Imported shape not shown; stored=$stored", shown)
            val overlay = activity!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
            instrumentation.runOnMainSync { assertEquals("arena_custom_shape", overlay.activeCustomShape?.name) }
            val frame = device.findObject(By.res(context.packageName, "cropImageView"))!!.visibleBounds
            assertTrue(device.swipe(frame.centerX() - 70, frame.centerY() - 70, frame.centerX() + 70, frame.centerY() + 70, 25))
            instrumentation.runOnMainSync {
                assertNotNull("Drawing custom shape through the window failed", overlay.activeShape)
                assertEquals("Custom shape ID lost", overlay.activeCustomShape?.id, overlay.activeShape?.customShapeId)
            }
            capture("markup-shape-import-$mode-drawn")
            val chip = requireNotNull(device.findObject(By.text("arena_custom_shape")))
            val beforeDelete = manager.getCustomShapes(context)
            val activeId = overlay.activeCustomShape?.id
            chip.longClick()
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.editor_shape_delete_title))), 5000))
            capture("markup-shape-import-$mode-delete-dialog")
            device.findObject(By.text(context.getString(R.string.delete)))!!.click()
            assertTrue("Delete confirmation did not close", device.wait(
                Until.gone(By.text(context.getString(R.string.editor_shape_delete_title))), 5000))
            instrumentation.waitForIdleSync()
            var afterDelete = emptyList<org.lineageos.glimpse.models.CustomShape>()
            instrumentation.runOnMainSync { afterDelete = manager.getCustomShapes(context) }
            if (afterDelete.any { it.name == "arena_custom_shape" }) capture("markup-shape-import-$mode-delete-result")
            assertTrue("Custom shape was not deleted; activeId=$activeId before=${beforeDelete.map { it.id to it.name }} after=${afterDelete.map { it.id to it.name }} original=${original.map { it.id to it.name }}", afterDelete.none { it.name == "arena_custom_shape" })
        } finally {
            manager.saveCustomShapes(context, original)
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun markupTextResizeAndRotateHaveSeparateWindowHandles() {
        assumeTrue("Separate text handles are candidate-only", isCandidate())
        editor(); tool(R.string.editor_markup_text)
        val overlay = activity!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
        val crop = activity!!.findViewById<CropImageView>(R.id.cropImageView)
        instrumentation.runOnMainSync { overlay.addTextItem("Window gesture", crop.width / 2f, crop.height / 2f) }
        // This candidate-only test still has to compile against baseline sources,
        // where the text rotation API and conversion method do not exist.
        fun textRotation(item: Any): Float = item.javaClass.getMethod("getRotation").invoke(item) as Float
        fun viewLengthToBase(length: Float): Float =
            overlay.javaClass.getMethod("viewLengthToBase", Float::class.javaPrimitiveType).invoke(overlay, length) as Float
        fun point(rotate: Boolean): IntArray {
            val result = IntArray(2)
            instrumentation.runOnMainSync {
                val item = requireNotNull(overlay.activeText)
                val bounds = android.graphics.RectF().also { item.computeBounds(it) }
                val coords = if (rotate) floatArrayOf(bounds.centerX(), bounds.top - viewLengthToBase(36f * context.resources.displayMetrics.density))
                    else floatArrayOf(bounds.right, bounds.bottom)
                requireNotNull(overlay.imageMatrixProvider).invoke().mapPoints(coords)
                val loc = IntArray(2); overlay.getLocationOnScreen(loc)
                result[0] = loc[0] + coords[0].toInt(); result[1] = loc[1] + coords[1].toInt()
            }
            return result
        }
        val resize = point(false)
        assertTrue(device.swipe(resize[0], resize[1], resize[0] + 35, resize[1] + 35, 20))
        device.waitForIdle()
        instrumentation.runOnMainSync {
            val item = requireNotNull(overlay.activeText)
            assertTrue("Resize did not change scale", item.scale > 1f)
            assertEquals("Resize rotated text", 0f, textRotation(item), .01f)
        }
        val rotate = point(true)
        assertTrue(device.swipe(rotate[0], rotate[1], rotate[0] + 55, rotate[1] + 25, 20))
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertTrue("Rotate handle did not rotate text", kotlin.math.abs(textRotation(requireNotNull(overlay.activeText))) > 1f)
        }
        capture("markup-text-separate-handles")
    }

    @Test fun markupOverwriteWritesSyntheticOriginal() {
        assumeTrue("Overwrite result is candidate-only", isCandidate())
        editor()
        val uri = images.first()
        val replacement = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val out = java.io.ByteArrayOutputStream()
        replacement.compress(Bitmap.CompressFormat.JPEG, 95, out); replacement.recycle()
        instrumentation.runOnMainSync {
            val method = ImageEditorActivity::class.java.getDeclaredMethod("overwrite", ByteArray::class.java, Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(activity, out.toByteArray(), false)
        }
        val deadline = System.currentTimeMillis() + 10000
        while (activity?.isFinishing != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Overwrite did not finish", activity?.isFinishing == true)
        val decoded = requireNotNull(context.contentResolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it) }
        assertNotNull("Overwritten image could not be decoded", decoded)
        assertEquals(48, decoded!!.width); assertEquals(48, decoded.height); decoded.recycle()
    }

    @Test fun markupMultilineFontAndAlignment() {
        assumeTrue("Text layout is candidate-only", isCandidate())
        editor(); tool(R.string.editor_markup_text)
        val overlay = activity!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
        val crop = activity!!.findViewById<CropImageView>(R.id.cropImageView)
        instrumentation.runOnMainSync {
            overlay.addTextItem("Hello 中文 line one\nA longer line wraps inside the text box", crop.width / 2f, crop.height / 2f)
        }
        fun stringId(name: String): Int = context.resources.getIdentifier(name, "string", context.packageName)
            .also { assertTrue("Missing resource $name", it != 0) }
        subtool(stringId("markup_font")); capture("markup-text-fonts")
        subtool(stringId("markup_alignment"))
        listOf(stringId("markup_align_left") to "markup-text-align-left",
            stringId("markup_align_center") to "markup-text-align-center",
            stringId("markup_align_right") to "markup-text-align-right").forEach { (id, name) ->
            val option = device.wait(Until.findObject(By.desc(context.getString(id))), 5000)
            assertNotNull("Alignment option missing", option)
            option!!.click(); device.waitForIdle(); capture(name)
        }
        instrumentation.runOnMainSync {
            val item = requireNotNull(overlay.activeText)
            val alignment = item.javaClass.getDeclaredField("alignment").apply { isAccessible = true }
            assertEquals("RIGHT", alignment.get(item).toString())
            val rect = android.graphics.RectF().also { item.computeBounds(it) }
            assertTrue("Multiline layout has only one line of height", rect.height() > item.effectiveTextSize * 2)
            assertTrue("Alignment undo failed", overlay.undo())
            assertEquals("CENTER", alignment.get(item).toString())
            assertTrue("Alignment redo failed", overlay.javaClass.getMethod("redo").invoke(overlay) as Boolean)
            assertEquals("RIGHT", alignment.get(item).toString())
            val original = item.copy()
            item.hasBackgroundPill = false
            item.text = "Hello 中文\nHi"
            item.centerX = 256f; item.centerY = 256f
            item.scale = 1f; item.baseTextSize = 30f
            val width = item.javaClass.getDeclaredField("boxWidth").apply { isAccessible = true }
            val oldWidth = width.getFloat(item); width.setFloat(item, 300f)
            val bounds = android.graphics.RectF().also { item.computeBounds(it) }
            val enumClass = alignment.type.enumConstants
            fun render(name: String): Bitmap {
                alignment.set(item, enumClass.first { it.toString() == name })
                return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also {
                    item.draw(android.graphics.Canvas(it))
                }
            }
            fun leftEdge(b: Bitmap): Int {
                for (x in 0 until b.width) for (y in 0 until b.height) if (b.getPixel(x, y) != Color.TRANSPARENT) return x
                return b.width
            }
            val left = render("LEFT"); val center = render("CENTER"); val right = render("RIGHT")
            assertTrue("Left, center and right did not alter glyph positions", leftEdge(left) < leftEdge(center) && leftEdge(center) < leftEdge(right))
            val after = android.graphics.RectF().also { item.computeBounds(it) }
            assertEquals("Alignment moved the text frame", bounds, after)
            val direct = right
            val exported = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val getBounds = overlay.javaClass.getMethod("getBaseBoundsProvider")
            val setBounds = overlay.javaClass.getMethod("setBaseBoundsProvider", kotlin.jvm.functions.Function0::class.java)
            val oldBounds = getBounds.invoke(overlay)
            val exportBounds: () -> android.graphics.RectF = { android.graphics.RectF(0f, 0f, 512f, 512f) }
            setBounds.invoke(overlay, exportBounds)
            overlay.javaClass.getMethod("rasterize", android.graphics.Canvas::class.java, android.graphics.Matrix::class.java)
                .invoke(overlay, android.graphics.Canvas(exported), android.graphics.Matrix())
            setBounds.invoke(overlay, oldBounds)
            assertTrue("Direct text layout differs from export", direct.sameAs(exported))
            left.recycle(); center.recycle(); right.recycle(); exported.recycle()
            item.text = original.text; item.centerX = original.centerX; item.centerY = original.centerY
            item.scale = original.scale; item.baseTextSize = original.baseTextSize
            item.hasBackgroundPill = original.hasBackgroundPill; width.setFloat(item, oldWidth)
        }
    }

    @Test fun markupFontImportValidationAndRecreation() {
        assumeTrue("Font import is candidate-only", isCandidate())
        editor(); tool(R.string.editor_markup_text)
        val fonts = Class.forName("org.lineageos.glimpse.models.MarkupFonts")
        val instance = fonts.getField("INSTANCE").get(null)
        val importMethod = fonts.getMethod("importFont", android.content.Context::class.java, Uri::class.java)
        fun importFile(file: File): String? = importMethod.invoke(instance, context, Uri.fromFile(file)) as? String
        val cache = File(context.cacheDir, "font-validation").apply { mkdirs() }
        val fake = File(cache, "spoof.ttf").apply { writeText("not a font") }
        val huge = File(cache, "large.otf").apply { outputStream().use { it.write(ByteArray(8 * 1024 * 1024 + 1)) } }
        val collection = File(cache, "collection.ttf").apply { writeBytes("ttcf00000000".toByteArray()) }
        assertEquals("Spoof accepted", null, importFile(fake))
        assertEquals("Oversized font accepted", null, importFile(huge))
        assertEquals("Collection accepted", null, importFile(collection))
        val systemFont = File("/system/fonts/Roboto-Regular.ttf")
        assertTrue("Expected emulator TTF is missing", systemFont.isFile)
        val valid = File(cache, "misnamed.otf").apply { systemFont.copyTo(this, overwrite = true) }
        val ttfId = requireNotNull(importFile(valid)) { "System TTF rejected" }
        assertTrue("TTF ID is not stable", ttfId.matches(Regex("[0-9a-f]{64}")))
        assertEquals(ttfId, importFile(valid))
        val otf = File(context.getExternalFilesDir(null), "markup-test-source-sans.otf")
        assertTrue("Pinned OFL OTF fixture is missing", otf.isFile)
        assertTrue("OTF fixture does not have an OTTO signature", otf.inputStream().use {
            val bytes = ByteArray(4); it.read(bytes) == 4 && String(bytes, Charsets.US_ASCII) == "OTTO"
        })
        // Return a real content URI through the existing OpenDocument contract;
        // the monitor substitutes only the picker result, not the import path.
        val otfValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "markup-fixture.otf")
            put(MediaStore.Downloads.MIME_TYPE, "font/otf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
        }
        val fontUri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, otfValues))
        try {
            context.contentResolver.openOutputStream(fontUri)!!.use { otf.inputStream().use { input -> input.copyTo(it) } }
            val fontPage = context.resources.getIdentifier("markup_font", "string", context.packageName)
            val importLabel = context.resources.getIdentifier("markup_font_import", "string", context.packageName)
            assertTrue(fontPage != 0 && importLabel != 0)
            subtool(fontPage)
            val importButton = device.wait(Until.findObject(By.text(context.getString(importLabel))), 5000)
            assertNotNull("Import entry unavailable", importButton)
            val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
            }
            val cancelled = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            val before = (fonts.getMethod("ids").invoke(instance) as List<*>).toSet()
            importButton!!.click(); instrumentation.waitForIdleSync(); instrumentation.removeMonitor(cancelled)
            assertEquals("Cancelled picker changed font library", before, (fonts.getMethod("ids").invoke(instance) as List<*>).toSet())
            val accepted = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(fontUri)), true)
            importButton.click(); instrumentation.waitForIdleSync(); instrumentation.removeMonitor(accepted)
            val id = MessageDigest.getInstance("SHA-256").digest(otf.readBytes()).joinToString("") { "%02x".format(it) }
            val deadline = System.currentTimeMillis() + 15000
            while (id !in (fonts.getMethod("ids").invoke(instance) as List<*>) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("SAF OTF import did not persist", id in (fonts.getMethod("ids").invoke(instance) as List<*>))
            assertTrue("Different fonts have the same ID", id != ttfId)
            assertEquals("OTF ID is not stable", id, importFile(otf))
            capture("markup-text-imported-fonts")
        } finally {
            context.contentResolver.delete(fontUri, null, null)
        }
        val id = MessageDigest.getInstance("SHA-256").digest(otf.readBytes()).joinToString("") { "%02x".format(it) }
        val truncated = File(cache, "truncated.otf").apply { writeBytes(otf.readBytes().copyOf(100)) }
        assertEquals("Truncated OTF accepted", null, importFile(truncated))
        val overlay = activity!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
        val crop = activity!!.findViewById<CropImageView>(R.id.cropImageView)
        instrumentation.runOnMainSync {
            overlay.addTextItem("Font 中文", crop.width / 2f, crop.height / 2f)
            val item = requireNotNull(overlay.activeText)
            val field = item.javaClass.getDeclaredField("fontId").apply { isAccessible = true }
            val change: () -> Unit = { field.set(item, id) }
            overlay.javaClass.getMethod("editAnnotation", kotlin.jvm.functions.Function0::class.java).invoke(overlay, change)
            assertTrue("Font undo failed", overlay.undo())
            assertEquals("sans", field.get(item))
            assertTrue("Font redo failed", overlay.javaClass.getMethod("redo").invoke(overlay) as Boolean)
            assertEquals(id, field.get(item))
            val editText = overlay.javaClass.getMethod("editText", item.javaClass, String::class.java)
            editText.invoke(overlay, item, "Changed 中文\nsecond line")
            assertEquals("Changed 中文\nsecond line", item.text)
            assertTrue("Text undo failed", overlay.undo())
            assertEquals("Font 中文", item.text)
            assertTrue("Text redo failed", overlay.javaClass.getMethod("redo").invoke(overlay) as Boolean)
            assertEquals("Changed 中文\nsecond line", item.text)
        }
        val monitor = instrumentation.addMonitor(ImageEditorActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { activity!!.recreate() }
        val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 15000)
        instrumentation.removeMonitor(monitor)
        assertNotNull("Activity was not recreated", recreated)
        activity = recreated
        device.waitForIdle()
        val restored = recreated!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
        instrumentation.runOnMainSync {
            val item = restored.annotations.filterIsInstance<org.lineageos.glimpse.ui.views.AnnotationItem.Text>().single()
            val field = item.javaClass.getDeclaredField("fontId").apply { isAccessible = true }
            assertEquals("Font ID lost during recreation", id, field.get(item))
            assertTrue("Imported font file missing", File(context.filesDir, "markup-fonts/$id.font").isFile)
            val ids = fonts.getMethod("ids").invoke(instance) as List<*>
            assertTrue("Imported OTF is not selectable after recreation", ids.contains(id))
        }
        // Re-enter the editor using window input and select the retained object.
        val deadline = System.currentTimeMillis() + 20000
        while (recreated.findViewById<CropImageView>(R.id.cropImageView).getDisplayBitmap() == null &&
            System.currentTimeMillis() < deadline) Thread.sleep(200)
        var markup = device.findObject(By.text(context.getString(R.string.editor_tab_markup)))
        if (markup == null) {
            val tabs = device.findObject(By.res(context.packageName, "categoryTabsScroll"))!!
            val r = tabs.visibleBounds
            device.swipe(r.right - 15, r.centerY(), r.left + 15, r.centerY(), 25)
            markup = device.findObject(By.text(context.getString(R.string.editor_tab_markup)))
        }
        assertNotNull("Markup category missing after recreation", markup)
        markup!!.click(); tool(R.string.editor_markup_text)
        val point = FloatArray(2)
        instrumentation.runOnMainSync {
            val item = restored.annotations.filterIsInstance<org.lineageos.glimpse.ui.views.AnnotationItem.Text>().single()
            point[0] = item.centerX; point[1] = item.centerY
            @Suppress("UNCHECKED_CAST")
            val provider = restored.javaClass.getMethod("getImageMatrixProvider").invoke(restored) as () -> android.graphics.Matrix
            provider().mapPoints(point)
            val screen = IntArray(2); restored.getLocationOnScreen(screen)
            point[0] += screen[0]; point[1] += screen[1]
        }
        device.click(point[0].toInt(), point[1].toInt()); device.waitForIdle()
        instrumentation.runOnMainSync { assertNotNull("Retained text could not be selected", restored.activeText) }
        subtool(context.resources.getIdentifier("markup_font", "string", context.packageName))
        val optionLabel = context.resources.getIdentifier("markup_font_option", "string", context.packageName)
        val optionDescription = context.getString(optionLabel, id.take(12))
        var chip = device.findObject(By.desc(optionDescription))
        if (chip == null) {
            val options = device.findObject(By.res(context.packageName, "markupOptionsScroll"))!!
            val r = options.visibleBounds
            device.swipe(r.right - 10, r.centerY(), r.left + 10, r.centerY(), 20)
            chip = device.findObject(By.desc(optionDescription))
        }
        assertNotNull("Retained imported OTF is not visible in font choices", chip)
        chip!!.click(); device.waitForIdle()
        instrumentation.runOnMainSync {
            val item = requireNotNull(restored.activeText)
            val field = item.javaClass.getDeclaredField("fontId").apply { isAccessible = true }
            assertEquals("Selected retained font changed", id, field.get(item))
        }
        capture("markup-text-imported-recreated")
        cache.deleteRecursively()
    }

    @Test fun markupImportedFontCropAndSavedOutput() {
        assumeTrue("Text export scenario is candidate-only", isCandidate())
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun outputs(): Set<Uri> {
            val found = mutableSetOf<Uri>()
            context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("Pictures/Glimpse/", "Glimpse_Edit_%"), null)?.use { cursor ->
                while (cursor.moveToNext()) found.add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
            return found
        }
        val before = outputs()
        val previousSize = device.executeShellCommand("wm size")
        val overrideSize = Regex("Override size: (\\d+x\\d+)").find(previousSize)?.groupValues?.get(1)
        device.executeShellCommand("wm size 720x1520")
        device.waitForIdle()
        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val previous = preferences.getString("edit_save_behavior", null)
        preferences.edit().putString("edit_save_behavior", "always_save_as_new").commit()
        try {
            editor(); tool(R.string.editor_markup_text)
            val crop = activity!!.findViewById<CropImageView>(R.id.cropImageView)
            val overlay = activity!!.findViewById<AnnotationOverlayView>(R.id.annotationOverlayView)
            val fixture = File(context.getExternalFilesDir(null), "markup-test-source-sans.otf")
            assertTrue("OpenType fixture missing", fixture.isFile)
            val fonts = Class.forName("org.lineageos.glimpse.models.MarkupFonts")
            val fontId = requireNotNull(fonts.getMethod("importFont", android.content.Context::class.java, Uri::class.java)
                .invoke(fonts.getField("INSTANCE").get(null), context, Uri.fromFile(fixture)) as? String)
            instrumentation.runOnMainSync {
                crop.javaClass.getMethod("applyPresetRatio", Float::class.javaPrimitiveType, Float::class.javaObjectType)
                    .invoke(crop, 1f, 0f)
                overlay.addTextItem("Custom font 中文\nMultiline crop preview", crop.width / 2f, crop.height / 2f)
                val item = requireNotNull(overlay.activeText)
                item.hasBackgroundPill = false; item.color = Color.WHITE
                item.javaClass.getDeclaredField("fontId").apply { isAccessible = true }.set(item, fontId)
                val alignment = item.javaClass.getDeclaredField("alignment").apply { isAccessible = true }
                alignment.set(item, alignment.type.enumConstants.first { it.toString() == "RIGHT" })
                overlay.javaClass.getMethod("constrainActiveText").invoke(overlay)
                val textBounds = android.graphics.RectF().also { item.computeBounds(it) }
                @Suppress("UNCHECKED_CAST")
                val matrix = overlay.javaClass.getMethod("getImageMatrixProvider").invoke(overlay) as () -> android.graphics.Matrix
                matrix().mapRect(textBounds)
                val frame = requireNotNull(activity!!.javaClass.getDeclaredMethod("editorFrame").apply { isAccessible = true }.invoke(activity))
                val snapshot = frame.javaClass.getDeclaredField("crop").apply { isAccessible = true }.get(frame)
                val visible = snapshot.javaClass.getMethod("getViewBounds").invoke(snapshot) as android.graphics.RectF
                assertTrue("Text frame extends beyond the cropped preview", textBounds.left >= visible.left - 1f &&
                    textBounds.top >= visible.top - 1f && textBounds.right <= visible.right + 1f && textBounds.bottom <= visible.bottom + 1f)
                overlay.invalidate()
            }
            device.findObject(By.res(context.packageName, "subEditDoneButton"))!!.click()
            device.waitForIdle(); capture("markup-text-crop-preview")
            var expected = byteArrayOf()
            instrumentation.runOnMainSync {
                val frameMethod = activity!!.javaClass.getDeclaredMethod("editorFrame").apply { isAccessible = true }
                val frame = requireNotNull(frameMethod.invoke(activity))
                val cropField = frame.javaClass.getDeclaredField("crop").apply { isAccessible = true }
                val matrixField = frame.javaClass.getDeclaredField("baseToOutput").apply { isAccessible = true }
                val snapshot = cropField.get(frame)
                val bitmap = snapshot.javaClass.getMethod("getBitmap").invoke(snapshot) as Bitmap
                val source = snapshot.javaClass.getMethod("getSource").invoke(snapshot) as Rect
                assertTrue("Crop did not change image dimensions", source.width() < bitmap.width || source.height() < bitmap.height)
                val result = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(result)
                canvas.drawBitmap(bitmap, source, Rect(0, 0, result.width, result.height), null)
                overlay.javaClass.getMethod("rasterize", android.graphics.Canvas::class.java, android.graphics.Matrix::class.java)
                    .invoke(overlay, canvas, matrixField.get(frame))
                val stream = java.io.ByteArrayOutputStream()
                assertTrue(result.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                expected = stream.toByteArray(); result.recycle()
            }
            val save = device.findObject(By.res(context.packageName, "saveButton"))
            assertNotNull("Editor save unavailable", save); save!!.click()
            val deadline = System.currentTimeMillis() + 30000
            while (outputs().subtract(before).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(200)
            val created = outputs().subtract(before)
            assertEquals("Expected one saved edit", 1, created.size)
            val saved = created.single()
            val actual = context.contentResolver.openInputStream(saved)!!.use { it.readBytes() }
            assertTrue("Saved crop differs from the text layout render", expected.contentEquals(actual))
            activity = null
            start(ImageEditorActivity.createIntent(context, saved, "image/jpeg"))
            val loadDeadline = System.currentTimeMillis() + 20000
            while (activity!!.findViewById<CropImageView>(R.id.cropImageView).getDisplayBitmap() == null &&
                System.currentTimeMillis() < loadDeadline) Thread.sleep(200)
            assertNotNull("Saved output could not be opened", activity!!.findViewById<CropImageView>(R.id.cropImageView).getDisplayBitmap())
            capture("markup-text-export")
        } finally {
            activity?.finish(); activity = null
            outputs().subtract(before).forEach { context.contentResolver.delete(it, null, null) }
            val edit = preferences.edit()
            if (previous == null) edit.remove("edit_save_behavior") else edit.putString("edit_save_behavior", previous)
            edit.commit()
            device.executeShellCommand(if (overrideSize == null) "wm size reset" else "wm size $overrideSize")
            device.waitForIdle()
        }
    }

    @Test fun markupFontPageUsesSpacedTouchTargets() {
        assumeTrue("Font page is candidate-only", isCandidate())
        editor(); tool(R.string.editor_markup_text)
        val fontPage = context.resources.getIdentifier("markup_font", "string", context.packageName)
        val importLabel = context.resources.getIdentifier("markup_font_import", "string", context.packageName)
        assertTrue("Font resources missing", fontPage != 0 && importLabel != 0)
        subtool(fontPage)
        instrumentation.runOnMainSync {
            val root = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
            fun findGroup(parent: ViewGroup): com.google.android.material.chip.ChipGroup? {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is com.google.android.material.chip.ChipGroup) return child
                    if (child is ViewGroup) findGroup(child)?.let { return it }
                }
                return null
            }
            fun findText(parent: ViewGroup, text: String): TextView? {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is TextView && child.text.toString() == text) return child
                    if (child is ViewGroup) findText(child, text)?.let { return it }
                }
                return null
            }
            val group = requireNotNull(findGroup(root)) { "Font choices missing" }
            assertTrue("Built-in fonts missing", group.childCount >= 3)
            val density = context.resources.displayMetrics.density
            val min = (48 * density).toInt()
            val gap = (8 * density).toInt()
            val chips = (0 until group.childCount).map { group.getChildAt(it) }
            chips.forEach { chip ->
                assertTrue("Font choice lacks 48dp touch target", chip.width >= min && chip.height >= min)
                assertTrue("Font choice lacks accessibility label", !chip.contentDescription.isNullOrBlank())
                assertTrue("Font choice is not clickable", chip.isClickable)
            }
            for (i in 1 until chips.size) {
                assertTrue("Font choices are too close", chips[i].left - chips[i - 1].right >= gap)
                assertTrue("Font choices are misaligned", kotlin.math.abs(chips[i].top - chips[0].top) <= 1)
            }
            val importButton = requireNotNull(findText(root, context.getString(importLabel)))
            assertTrue("Import needs a 48dp touch target", importButton.width >= min && importButton.height >= min)
            assertTrue("Import must be clickable", importButton.isClickable)
            assertEquals("Import text size", 12f * context.resources.displayMetrics.scaledDensity, importButton.textSize, 0.5f)
            val a = IntArray(2); val b = IntArray(2)
            group.getLocationOnScreen(a); importButton.getLocationOnScreen(b)
            assertTrue("Import overlaps the font choices", b[0] - (a[0] + group.width) >= gap)
        }
        capture("markup-font-styled")
    }

    @Test fun markupTextControlsHaveSeparateTouchTargets() {
        assumeTrue("Text control spacing is candidate-only", isCandidate())
        editor(); tool(R.string.editor_markup_text)
        val dockId = context.resources.getIdentifier("markupSubtoolsRow", "id", context.packageName)
        assertTrue("Missing fixed subtool dock", dockId != 0)
        fun findText(group: ViewGroup, label: String): TextView? {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is TextView && child.text.toString() == label) return child
                if (child is ViewGroup) findText(child, label)?.let { return it }
            }
            return null
        }
        instrumentation.runOnMainSync {
            val dock = activity!!.findViewById<ViewGroup>(dockId)
            val min = (48 * context.resources.displayMetrics.density).toInt()
            assertEquals(5, dock.childCount)
            val font = activity!!.resources.getIdentifier("markup_font", "string", activity!!.packageName)
            val alignment = activity!!.resources.getIdentifier("markup_alignment", "string", activity!!.packageName)
            assertTrue("Font and alignment resources missing", font != 0 && alignment != 0)
            val expectedPills = listOf(R.string.markup_text_section, font,
                alignment, R.string.editor_markup_text_bg, R.string.markup_color_section)
            expectedPills.forEach { label ->
                assertTrue("Missing expected text subtool", (0 until dock.childCount).any {
                    dock.getChildAt(it).contentDescription == activity!!.getString(label)
                })
            }
            val actionPill = (0 until dock.childCount).map { dock.getChildAt(it) }
                .first { it.contentDescription == activity!!.getString(R.string.markup_text_section) }
            val backgroundPill = (0 until dock.childCount).map { dock.getChildAt(it) }
                .first { it.contentDescription == activity!!.getString(R.string.editor_markup_text_bg) }
            val a = IntArray(2); val b = IntArray(2)
            actionPill.getLocationOnScreen(a); backgroundPill.getLocationOnScreen(b)
            assertTrue("Text controls overlap or lack spacing", b[0] - (a[0] + actionPill.width) >= (11f * context.resources.displayMetrics.density).toInt())
            for (item in listOf(actionPill, backgroundPill)) {
                assertTrue("Subtool touch target too small", item.width >= min && item.height >= min)
            }
            val root = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
            val action = requireNotNull(findText(root, activity!!.getString(R.string.markup_text_action)))
            assertTrue("Text action touch target too small", action.height >= min)
            assertTrue("Background must not appear on the edit page", findText(root, activity!!.getString(R.string.editor_markup_text_bg)) == null)
        }
        subtool(R.string.editor_markup_text_bg)
        instrumentation.runOnMainSync {
            val root = activity!!.findViewById<ViewGroup>(R.id.markupOptionsContent)
            val background = requireNotNull(findText(root, activity!!.getString(R.string.editor_markup_text_bg)))
            assertTrue("Background touch target too small", background.height >= (48f * context.resources.displayMetrics.density).toInt())
            assertTrue("Text action must not appear on the background page", findText(root, activity!!.getString(R.string.markup_text_action)) == null)
            assertTrue("Background control must be clickable", background.isClickable)
        }
        subtool(R.string.markup_color_section)
        assertNotNull("Text color choices missing", device.findObject(By.desc(context.getString(R.string.markup_color_black))))
    }

    @Test fun collagePreviewGesturesStayOnTheirStartingPhoto() {
        assumeTrue("Collage gesture changes are candidate-only", isCandidate())
        collage(2)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        instrumentation.runOnMainSync {
            val edge = minOf(view.width, view.height).toFloat()
            val left = (view.width - edge) / 2f
            val top = (view.height - edge) / 2f
            val x = left + edge * .25f
            val y = top + edge * .5f
            view.photos[0].zoom = 2f
            var time = SystemClock.uptimeMillis()
            var down = time
            fun event(action: Int, points: List<Pair<Float, Float>>) {
                if (action == MotionEvent.ACTION_DOWN) down = time
                val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply {
                    id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray()
                val coordinates = points.map { (px, py) -> MotionEvent.PointerCoords().apply {
                    this.x = px; this.y = py; pressure = 1f; size = 1f
                } }.toTypedArray()
                val motion = MotionEvent.obtain(down, time, action, points.size, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(view.onTouchEvent(motion)) } finally { motion.recycle() }
                time += 20
            }
            val beforeX = view.photos[0].offsetX
            val beforeY = view.photos[0].offsetY
            event(MotionEvent.ACTION_DOWN, listOf(x to y))
            event(MotionEvent.ACTION_MOVE, listOf((x + edge * .06f) to (y + edge * .06f)))
            event(MotionEvent.ACTION_UP, listOf((x + edge * .06f) to (y + edge * .06f)))
            assertTrue("Direct drag did not pan", view.photos[0].offsetX != beforeX || view.photos[0].offsetY != beforeY)
            val oldZoom = view.photos[0].zoom
            val otherZoom = view.photos[1].zoom
            val first = (x - edge * .08f) to y
            val second = (x + edge * .08f) to y
            event(MotionEvent.ACTION_DOWN, listOf(first))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(first, second))
            event(MotionEvent.ACTION_MOVE, listOf((x - edge * .16f) to y, (x + edge * .16f) to y))
            event(MotionEvent.ACTION_MOVE, listOf((x - edge * .20f) to y, (x + edge * .20f) to y))
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(first, second))
            event(MotionEvent.ACTION_UP, listOf(first))
            assertTrue("Pinch did not zoom the starting cell", view.photos[0].zoom > oldZoom)
            assertEquals(otherZoom, view.photos[1].zoom, 0f)
            val after = view.photos[0].zoom
            event(MotionEvent.ACTION_DOWN, listOf(x to y))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(x to y, (left + edge * .75f) to y))
            event(MotionEvent.ACTION_MOVE, listOf((x - edge * .1f) to y, (left + edge * .85f) to y))
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(x to y, (left + edge * .75f) to y))
            event(MotionEvent.ACTION_UP, listOf(x to y))
            assertEquals("Cross-cell pinch changed zoom", after, view.photos[0].zoom, 0f)
            assertEquals(otherZoom, view.photos[1].zoom, 0f)
            assertEquals(0, view.selected)
        }
    }

    @Test fun collageSplitControlAndBottomSave() {
        assumeTrue("Collage divider changes are candidate-only", isCandidate())
        collage(4)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        val points = IntArray(4)
        var before = 0f
        instrumentation.runOnMainSync {
            val save = activity!!.findViewById<View>(R.id.collageSave)
            val root = activity!!.findViewById<View>(R.id.collageRoot)
            val saveLoc = IntArray(2); val rootLoc = IntArray(2)
            save.getLocationOnScreen(saveLoc); root.getLocationOnScreen(rootLoc)
            assertTrue("Save not anchored near bottom", saveLoc[1] + save.height > rootLoc[1] + root.height * .85f)
            val cards = activity!!.findViewById<LinearLayout>(R.id.collageLayoutCards)
            assertTrue("Second collage layout is missing", cards.childCount > 1)
            assertTrue("Could not select the second layout", cards.getChildAt(1).performClick())
            assertEquals(1, view.layout)
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val edge = minOf(view.width, view.height)
            assertTrue("Collage preview has no size", edge > 0)
            val left = loc[0] + (view.width - edge) / 2
            val top = loc[1] + (view.height - edge) / 2
            before = CollageView::class.java.getDeclaredField("split").apply { isAccessible = true }.getFloat(view)
            points[0] = left + (edge * before).toInt(); points[1] = top + edge / 4
            points[2] = left + edge / 4; points[3] = points[1]
        }
        device.waitForIdle()
        // The divider is drawn in the preview and manipulated directly, not a Slider.
        assertTrue("Divider swipe could not be injected", device.swipe(points[0], points[1], points[2], points[3], 25))
        device.waitForIdle()
        instrumentation.runOnMainSync {
            val split = CollageView::class.java.getDeclaredField("split").apply { isAccessible = true }.getFloat(view)
            assertTrue("Divider drag did not change proportion", split < .48f && split < before - .05f)
            assertEquals(1, view.layout)
            val geometry = Class.forName("org.lineageos.glimpse.collage.CollageLayoutKt")
                .getDeclaredMethod("collageCells", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
            assertTrue("Divider drag did not change layout geometry", geometry.invoke(null, 4, view.layout, before) != geometry.invoke(null, 4, view.layout, split))
        }
    }

    @Test fun collageTwo() { collage(2); capture("collage-two") }
    @Test fun collageSelected() {
        collage(2)
        val preview = device.findObject(By.res(context.packageName, "collagePreview"))
        assertNotNull("Collage preview missing", preview)
        if (isCandidate()) {
            assertNotNull("Layout icons should be visible initially", device.findObject(By.res(context.packageName, "collageLayouts")))
            assertNull("Photo actions should initially be hidden", device.findObject(By.res(context.packageName, "collageActions")))
            for (name in listOf("collageModePhoto", "collageModeLayout", "collageZoom", "collageHint")) {
                assertEquals("Old controls must be removed: $name", 0, context.resources.getIdentifier(name, "id", context.packageName))
            }
        }
        val r = preview!!.visibleBounds
        device.click(r.right - r.width() / 4, r.centerY())
        if (isCandidate()) {
            assertTrue("Photo actions missing", device.wait(Until.hasObject(By.res(context.packageName, "collageEdit")), 10000))
            assertNotNull(device.findObject(By.res(context.packageName, "collageReplace")))
            assertNotNull(device.findObject(By.res(context.packageName, "collageDone")))
            assertNull("Layout choices must hide when a photo is selected", device.findObject(By.res(context.packageName, "collageLayouts")))
            assertNull("Save must hide when a photo is selected", device.findObject(By.res(context.packageName, "collageSave")))
        } else {
            assertTrue("Second collage photo was not selected", device.wait(
                Until.hasObject(By.textContains("Photo 2 of 2 selected")), 10000))
        }
        capture("collage-selected")
        if (isCandidate()) {
            device.findObject(By.res(context.packageName, "collageDone"))!!.click()
            assertNotNull("Layout choices did not return", device.wait(Until.findObject(By.res(context.packageName, "collageLayouts")), 5000))
            assertNotNull(device.findObject(By.res(context.packageName, "collageSave")))
            assertNull(device.findObject(By.res(context.packageName, "collageActions")))
        }
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
        assumeTrue("Preview reorder is candidate-only", isCandidate())
        collage(3)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        device.waitForIdle()
        val positions = IntArray(4)
        instrumentation.runOnMainSync {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val edge = minOf(view.width, view.height)
            val left = loc[0] + (view.width - edge) / 2
            val top = loc[1] + (view.height - edge) / 2
            positions[0] = left + edge / 4; positions[1] = top + edge / 4
            positions[2] = left + edge * 3 / 4; positions[3] = top + edge / 4
            view.photos[0].zoom = 2f
            view.photos[0].offsetX = .3f
            view.photos[1].zoom = 1.4f
        }
        // Inject a real window-level long press followed by a drag.
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
        instrumentation.runOnMainSync {
            assertEquals(listOf(images[1], images[0], images[2]), view.photos.map { it.uri })
            assertEquals(1, view.selected)
            assertEquals(2f, view.photos[1].zoom, 0f)
            assertEquals(.3f, view.photos[1].offsetX, 0f)
            assertEquals(1.4f, view.photos[0].zoom, 0f)
            activity!!.findViewById<LinearLayout>(R.id.collageLayoutCards).getChildAt(1).performClick()
            assertEquals(1, view.layout)
            assertEquals(listOf(images[1], images[0], images[2]), view.photos.map { it.uri })
            assertEquals(2f, view.photos[1].zoom, 0f)
        }
    }

    @Test fun collageSplitGestureChangesGeometry() {
        assumeTrue("Direct divider is candidate-only", isCandidate())
        collage(4)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        val points = IntArray(4)
        var before = 0f
        instrumentation.runOnMainSync {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val edge = minOf(view.width, view.height)
            val left = loc[0] + (view.width - edge) / 2
            val top = loc[1] + (view.height - edge) / 2
            points[0] = left + edge / 2; points[1] = top + edge / 4
            points[2] = left + edge / 3; points[3] = points[1]
            before = CollageView::class.java.getDeclaredField("split").apply { isAccessible = true }.getFloat(view)
        }
        assertTrue(device.swipe(points[0], points[1], points[2], points[3], 25))
        device.waitForIdle()
        instrumentation.runOnMainSync {
            val after = CollageView::class.java.getDeclaredField("split").apply { isAccessible = true }.getFloat(view)
            assertTrue("Divider gesture did not change proportion", after < before - .05f)
            val geometry = Class.forName("org.lineageos.glimpse.collage.CollageLayoutKt")
                .getDeclaredMethod("collageCells", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
            assertTrue("Divider did not change geometry", geometry.invoke(null, 4, view.layout, before) != geometry.invoke(null, 4, view.layout, after))
            assertEquals(images.take(4), view.photos.map { it.uri })
        }
        capture("collage-preview-split")
    }

    @Test fun collagePreviewForAllPhotoCounts() {
        assumeTrue("Preview-only controls are candidate-only", isCandidate())
        for (count in 2..6) {
            collage(count)
            val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
            instrumentation.runOnMainSync {
                assertEquals(count, view.photos.size)
                assertEquals(images.take(count), view.photos.map { it.uri })
                assertEquals(0, context.resources.getIdentifier("collageFilmstrip", "id", context.packageName))
            }
            capture("collage-preview-$count")
            instrumentation.runOnMainSync { activity?.finish() }
            device.waitForIdle()
        }
    }

    @Test fun collageLandscapeBottomSave() {
        assumeTrue("Preview-only controls are candidate-only", isCandidate())
        try {
            device.setOrientationLeft()
            collage(4)
            val save = device.wait(Until.findObject(By.res(context.packageName, "collageSave")), 10000)
            val root = device.findObject(By.res(context.packageName, "collageRoot"))
            assertNotNull(save); assertNotNull(root)
            val r = root!!.visibleBounds; val b = save!!.visibleBounds
            assertTrue("Save clipped in landscape", b.height() >= (48 * context.resources.displayMetrics.density).toInt())
            assertTrue("Save is outside safe area", b.bottom <= r.bottom)
            assertTrue("Save is not near the bottom", b.bottom > r.top + r.height() * .85f)
            capture("collage-preview-landscape")
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test fun collagePinchThroughWindow() {
        assumeTrue("Preview gestures are candidate-only", isCandidate())
        collage(2)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        var x = 0f; var y = 0f; var edge = 0f
        instrumentation.runOnMainSync {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            edge = minOf(view.width, view.height).toFloat()
            x = loc[0] + (view.width - edge) / 2 + edge * .25f
            y = loc[1] + (view.height - edge) / 2 + edge * .5f
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, points: List<Pair<Float, Float>>) {
            val props = points.indices.map { i -> MotionEvent.PointerProperties().apply {
                id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coords = points.map { (px, py) -> MotionEvent.PointerCoords().apply {
                this.x = px; this.y = py; pressure = 1f; size = 1f
            } }.toTypedArray()
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, props, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
            Thread.sleep(35)
        }
        val initial = listOf((x - edge * .05f) to y, (x + edge * .05f) to y)
        send(MotionEvent.ACTION_DOWN, listOf(initial[0]))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), initial)
        for (n in 1..6) {
            val distance = edge * (.05f + .015f * n)
            send(MotionEvent.ACTION_MOVE, listOf((x - distance) to y, (x + distance) to y))
        }
        val last = listOf((x - edge * .14f) to y, (x + edge * .14f) to y)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), last)
        send(MotionEvent.ACTION_UP, listOf(last[0]))
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertTrue("Window pinch did not zoom the starting cell", view.photos[0].zoom > 1f)
            assertEquals(1f, view.photos[1].zoom, 0f)
            assertEquals(images.take(2), view.photos.map { it.uri })
        }
    }

    @Test fun collagePreviewCancelAndTemplateIndependence() {
        assumeTrue("Preview reorder is candidate-only", isCandidate())
        collage(4)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        val point = IntArray(2)
        instrumentation.runOnMainSync {
            assertEquals("Filmstrip resource must be removed", 0,
                context.resources.getIdentifier("collageFilmstrip", "id", context.packageName))
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val edge = minOf(view.width, view.height)
            point[0] = loc[0] + (view.width - edge) / 2 + edge / 4
            point[1] = loc[1] + (view.height - edge) / 2 + edge / 4
            view.selected = 1
            view.photos[0].zoom = 2f
            view.photos[0].offsetX = .2f
            view.photos[0].offsetY = -.3f
        }
        fun gesture(cancel: Boolean) {
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, point[0].toFloat(), point[1].toFloat())
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 200)
            send(MotionEvent.ACTION_MOVE, 1f, 1f)
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 1f, 1f)
            device.waitForIdle()
        }
        for (cancel in listOf(false, true)) {
            gesture(cancel)
            instrumentation.runOnMainSync {
                assertEquals(images.take(4), view.photos.map { it.uri })
                assertEquals(1, view.selected)
                assertEquals(2f, view.photos[0].zoom, 0f)
                assertEquals(.2f, view.photos[0].offsetX, 0f)
                assertEquals(-.3f, view.photos[0].offsetY, 0f)
            }
        }
        instrumentation.runOnMainSync {
            val cards = activity!!.findViewById<LinearLayout>(R.id.collageLayoutCards)
            cards.getChildAt(1).performClick()
            assertEquals(1, view.layout)
            assertEquals(images.take(4), view.photos.map { it.uri })
        }
        capture("collage-preview-no-filmstrip")
    }

    @Test fun collageReplaceAndCancel() {
        // The baseline in the screenshot workflow predates the replace control.
        val id = context.resources.getIdentifier("collageReplace", "id", context.packageName)
        assumeTrue("Replacement unavailable in baseline", id != 0)
        collage(2)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        if (isCandidate()) {
            val r = device.findObject(By.res(context.packageName, "collagePreview"))!!.visibleBounds
            device.click(r.left + r.width() / 4, r.centerY())
            assertNotNull(device.wait(Until.findObject(By.res(context.packageName, "collageReplace")), 5000))
        }
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

    @Test fun collageEditReturnsPhotoAndPreservesComposition() {
        assumeTrue("Photo action mode is candidate-only", isCandidate())
        collage(2)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        val r = device.findObject(By.res(context.packageName, "collagePreview"))!!.visibleBounds
        device.click(r.left + r.width() / 4, r.centerY())
        assertNotNull(device.wait(Until.findObject(By.res(context.packageName, "collageEdit")), 5000))
        instrumentation.runOnMainSync { view.photos[0].zoom = 2f; view.photos[0].offsetX = .2f }
        val monitor = instrumentation.addMonitor(ImageEditorActivity::class.java.name,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(images[2])), true)
        device.findObject(By.res(context.packageName, "collageEdit"))!!.click()
        instrumentation.waitForIdleSync()
        instrumentation.removeMonitor(monitor)
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = view.photos.firstOrNull()?.uri == images[2] }
            if (done) break
            Thread.sleep(100)
        }
        instrumentation.runOnMainSync {
            assertEquals(listOf(images[2], images[1]), view.photos.map { it.uri })
            assertEquals(2f, view.photos[0].zoom, 0f)
            assertEquals(.2f, view.photos[0].offsetX, 0f)
        }
        assertNotNull(device.findObject(By.res(context.packageName, "collageDone")))
    }

    @Test fun collagePanThroughWindow() {
        assumeTrue("Preview gestures are candidate-only", isCandidate())
        collage(2)
        val view = activity!!.findViewById<CollageView>(R.id.collagePreview)
        val points = IntArray(4)
        instrumentation.runOnMainSync {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val edge = minOf(view.width, view.height)
            val x = loc[0] + (view.width - edge) / 2 + edge / 4
            val y = loc[1] + (view.height - edge) / 2 + edge / 2
            points[0] = x; points[1] = y; points[2] = x + edge / 12; points[3] = y + edge / 12
            view.photos[0].zoom = 2f
        }
        assertTrue(device.swipe(points[0], points[1], points[2], points[3], 25))
        instrumentation.runOnMainSync {
            assertTrue("Window drag did not pan", view.photos[0].offsetX != 0f || view.photos[0].offsetY != 0f)
            assertEquals(1f, view.photos[1].zoom, 0f)
            assertEquals(images.take(2), view.photos.map { it.uri })
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
                val save = activity!!.findViewById<View>(R.id.collageSave)
                val saveLoc = IntArray(2); val rootLoc = IntArray(2)
                save.getLocationOnScreen(saveLoc); root.getLocationOnScreen(rootLoc)
                assertTrue("Save outside short window", saveLoc[1] + save.height <= rootLoc[1] + root.height)
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
