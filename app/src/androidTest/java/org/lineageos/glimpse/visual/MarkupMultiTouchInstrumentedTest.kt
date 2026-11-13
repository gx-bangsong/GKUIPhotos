/* System-dispatched Markup multitouch and image-anchor regression tests.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.app.Activity
import android.content.ContentValues
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.lineageos.glimpse.ImageEditorActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ui.views.AnnotationItem
import org.lineageos.glimpse.ui.views.AnnotationOverlayView
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.ui.views.MarkupTool
import java.io.File

@RunWith(AndroidJUnit4::class)
class MarkupMultiTouchInstrumentedTest {
    @get:Rule val name = TestName()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private val device = UiDevice.getInstance(ins)
    private val trace = StringBuilder()
    private val media = mutableListOf<Uri>()
    private var activity: Activity? = null
    private lateinit var crop: CropImageView
    private lateinit var overlay: AnnotationOverlayView
    private var downTime = 0L
    private val position = IntArray(2)

    private fun publish(filename: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(ctx.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            check(ctx.contentResolver.update(uri, values, null, null) == 1)
        } catch (t: Throwable) { ctx.contentResolver.delete(uri, null, null); throw t }
    }

    private fun screenshot(label: String) {
        device.waitForIdle(); SystemClock.sleep(200)
        val file = File(ctx.cacheDir, "touch-${name.methodName}-$label.png")
        try {
            assertTrue("Unable to capture $label", device.takeScreenshot(file))
            publish(file.name, "image/png", file.readBytes())
        } finally { file.delete() }
    }

    private fun state(label: String) {
        ins.runOnMainSync {
            val matrix = requireNotNull(overlay.imageMatrixProvider).invoke()
            val values = FloatArray(9); matrix.getValues(values)
            trace.append("$label viewport=${crop.getTransformState()} baseToView=${values.toList()}\n")
            overlay.activeText?.let { trace.append("selected text base=(${it.centerX},${it.centerY}) scale=${it.scale}\n") }
            overlay.activeSticker?.let { trace.append("selected sticker base=(${it.centerX},${it.centerY}) scale=${it.scale} angle=${it.rotation}\n") }
            overlay.annotations.forEachIndexed { i, item ->
                val desc = when (item) {
                    is AnnotationItem.Doodle -> android.graphics.RectF().also { item.path.computeBounds(it, true) }.toString()
                    is AnnotationItem.Text -> "(${item.centerX},${item.centerY}) scale=${item.scale}"
                    is AnnotationItem.Sticker -> "(${item.centerX},${item.centerY}) scale=${item.scale}"
                    else -> item.javaClass.simpleName
                }
                trace.append("annotation[$i]=$desc\n")
            }
        }
    }

    @Before fun setup() {
        assumeTrue("candidate only", InstrumentationRegistry.getArguments().getString("uiVersion") != "baseline")
        trace.append("sourceSha=${InstrumentationRegistry.getArguments().getString("sourceSha") ?: "not supplied"}\n")
        trace.append("System UiAutomation events; screen pixels and image-base pixels\n")
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(278f, 248f, 294f, 264f, Paint().apply { color = Color.CYAN })
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "markup_touch_fixture.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GlimpseUiTest")
        }
        val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        media.add(uri)
        try { requireNotNull(ctx.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val intent = ImageEditorActivity.createIntent(ctx, uri, "image/png").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = ins.startActivitySync(intent)
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline && activity!!.findViewById<CropImageView>(R.id.cropImageView).getDisplayBitmap() == null) SystemClock.sleep(100)
        crop = activity!!.findViewById(R.id.cropImageView)
        overlay = activity!!.findViewById(R.id.annotationOverlayView)
        assertNotNull("Photo failed to load", crop.getDisplayBitmap())
        device.waitForIdle()
        var markup = device.findObject(By.text(ctx.getString(R.string.editor_tab_markup)))
        if (markup == null) {
            val scroll = requireNotNull(device.findObject(By.res(ctx.packageName, "categoryTabsScroll")))
            val b = scroll.visibleBounds
            device.swipe(b.right - 15, b.centerY(), b.left + 15, b.centerY(), 25)
            markup = device.wait(Until.findObject(By.text(ctx.getString(R.string.editor_tab_markup))), 5000)
        }
        requireNotNull(markup).click()
        val tool = requireNotNull(device.wait(Until.findObject(By.desc(ctx.getString(R.string.editor_markup_doodle))), 10000))
        tool.click()
        assertTrue(device.wait(Until.hasObject(By.desc(ctx.getString(R.string.editor_markup_doodle)).selected(true)), 10000))
        device.waitForIdle()
        ins.runOnMainSync { overlay.getLocationOnScreen(position); overlay.activeColor = Color.RED }
        trace.append("preview left=${position[0]} top=${position[1]} width=${overlay.width} height=${overlay.height}\n")
        state("initial")
    }

    @After fun cleanup() {
        if (activity != null) {
            try { screenshot("final") } finally {
                publish("touch-${name.methodName}-trace.txt", "text/plain", trace.toString().toByteArray())
                activity?.finish()
                media.forEach { ctx.contentResolver.delete(it, null, null) }
            }
        } else media.forEach { ctx.contentResolver.delete(it, null, null) }
    }

    private fun center() = (position[0] + overlay.width / 2f) to (position[1] + overlay.height / 2f)
    private fun send(action: Int, vararg points: Pair<Float, Float>) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val t = SystemClock.uptimeMillis()
        points.forEach { (x, y) -> assertTrue("Input outside preview", x >= position[0] && x < position[0] + overlay.width && y >= position[1] && y < position[1] + overlay.height) }
        val props = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(points.size) { i -> MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = 1f } }
        trace.append("event action=$action down=$downTime time=$t pointers=${points.toList()}\n")
        val e = MotionEvent.obtain(downTime, t, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("System input injection rejected", ins.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
        SystemClock.sleep(40)
    }
    private fun pinch(pan: Boolean = false) {
        val (x,y) = center()
        send(MotionEvent.ACTION_DOWN, (x-45f) to y)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), (x-45f) to y, (x+45f) to y)
        val a = if (pan) (x-25f) to (y+15f) else (x-70f) to y
        val b = if (pan) (x+65f) to (y+15f) else (x+70f) to y
        send(MotionEvent.ACTION_MOVE, a, b)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), a, b)
        send(MotionEvent.ACTION_UP, a)
        device.waitForIdle()
    }
    private fun transform(): Triple<Float, Float, Float> {
        var value = Triple(0f,0f,0f)
        ins.runOnMainSync { value = crop.getTransformState() }
        return value
    }

    @Test fun activeMarkupPinchZoomsPhoto() {
        screenshot("before"); val before = transform(); pinch(); state("after pinch"); screenshot("after")
        assertTrue("Pinch did not zoom photo", transform().first > before.first)
    }

    @Test fun activeMarkupTwoFingerPanMovesPhoto() {
        screenshot("before"); val before = transform(); pinch(pan = true); state("after pan"); screenshot("after")
        val after = transform()
        assertEquals("Parallel movement changed zoom", before.first, after.first, 0.001f)
        assertTrue("Parallel movement did not translate photo", after.second != before.second || after.third != before.third)
    }

    @Test fun interruptedStrokeDoesNotResumeAndFreshStrokeDraws() {
        val (x,y) = center()
        send(MotionEvent.ACTION_DOWN, (x-30f) to y)
        send(MotionEvent.ACTION_MOVE, (x-20f) to (y+5f))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), (x-20f) to (y+5f), (x+45f) to (y+5f))
        send(MotionEvent.ACTION_MOVE, (x-30f) to (y+5f), (x+65f) to (y+5f))
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), (x-30f) to (y+5f), (x+65f) to (y+5f))
        send(MotionEvent.ACTION_MOVE, (x+20f) to (y+20f))
        send(MotionEvent.ACTION_UP, (x+20f) to (y+20f))
        ins.runOnMainSync { assertTrue("Interrupted stroke remained", overlay.annotations.isEmpty()) }
        send(MotionEvent.ACTION_DOWN, (x-30f) to y)
        send(MotionEvent.ACTION_MOVE, x to y)
        send(MotionEvent.ACTION_UP, (x+30f) to y)
        state("after fresh stroke"); screenshot("fresh-stroke")
        ins.runOnMainSync { assertEquals(1, overlay.annotations.size); assertTrue(overlay.annotations[0] is AnnotationItem.Doodle) }
    }

    @Test fun selectedTextPinchTransformsPhotoNotObject() {
        ins.runOnMainSync { overlay.addTextItem("Test", overlay.width / 2f, overlay.height / 2f) }
        var before: AnnotationItem.Text? = null
        ins.runOnMainSync { before = requireNotNull(overlay.activeText).copy() }
        val photo = transform(); state("before selected text pinch"); screenshot("before")
        pinch(); state("after selected text pinch"); screenshot("after")
        assertTrue("Photo did not zoom", transform().first > photo.first)
        ins.runOnMainSync {
            val item = requireNotNull(overlay.activeText); val old = requireNotNull(before)
            assertEquals(old.centerX, item.centerX, .001f); assertEquals(old.centerY, item.centerY, .001f); assertEquals(old.scale, item.scale, .001f)
        }
    }

    @Test fun selectedStickerPinchTransformsPhotoNotObject() {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            ins.runOnMainSync { overlay.addSticker(bitmap) }
            var before: AnnotationItem.Sticker? = null
            ins.runOnMainSync { before = requireNotNull(overlay.activeSticker).copy() }
            val photo = transform(); state("before selected sticker pinch"); screenshot("before")
            pinch(); state("after selected sticker pinch"); screenshot("after")
            assertTrue("Photo did not zoom", transform().first > photo.first)
            ins.runOnMainSync {
                val item = requireNotNull(overlay.activeSticker); val old = requireNotNull(before)
                assertEquals(old.centerX, item.centerX, .001f); assertEquals(old.centerY, item.centerY, .001f)
                assertEquals(old.scale, item.scale, .001f); assertEquals(old.rotation, item.rotation, .001f)
            }
        } finally {
            ins.runOnMainSync { overlay.clear() }
            bitmap.recycle()
        }
    }

    private fun screenPoint(x: Float, y: Float): Pair<Float,Float> {
        val p = floatArrayOf(x,y)
        ins.runOnMainSync { requireNotNull(overlay.imageMatrixProvider).invoke().mapPoints(p); overlay.getLocationOnScreen(position) }
        return (p[0]+position[0]) to (p[1]+position[1])
    }
    private fun containsColor(bmp: Bitmap, x: Float, y: Float, expected: Int, radius: Int): Boolean {
        val l = (x.toInt()-radius).coerceAtLeast(0); val r = (x.toInt()+radius).coerceAtMost(bmp.width-1)
        val t = (y.toInt()-radius).coerceAtLeast(0); val b = (y.toInt()+radius).coerceAtMost(bmp.height-1)
        if (l > r || t > b) return false
        for (yy in t..b) for (xx in l..r) {
            val c = bmp.getPixel(xx,yy)
            val diff = kotlin.math.abs(Color.red(c)-Color.red(expected)) + kotlin.math.abs(Color.green(c)-Color.green(expected)) + kotlin.math.abs(Color.blue(c)-Color.blue(expected))
            if (diff < 100) return true
        }
        return false
    }
    private fun assertPreviewAnchors() {
        device.waitForIdle(); SystemClock.sleep(250)
        val mark = screenPoint(240f,230f); val feature = screenPoint(286f,256f)
        val image = requireNotNull(ins.uiAutomation.takeScreenshot())
        try {
            assertTrue("Red annotation not at image anchor", containsColor(image,mark.first,mark.second,Color.RED,12))
            assertTrue("Photo feature not at image anchor", containsColor(image,feature.first,feature.second,Color.CYAN,8))
        } finally { image.recycle() }
    }

    @Test fun exitingMarkupKeepsAnnotationAnchoredToPhoto() {
        val a=screenPoint(225f,230f); val b=screenPoint(240f,230f); val c=screenPoint(255f,230f)
        trace.append("stroke image=(225,230)-(255,230); cyan feature=(286,256)\n")
        send(MotionEvent.ACTION_DOWN,a); send(MotionEvent.ACTION_MOVE,b); send(MotionEvent.ACTION_UP,c)
        state("before exit"); assertPreviewAnchors(); screenshot("before-exit")
        ins.runOnMainSync { activity!!.findViewById<View>(R.id.subEditDoneButton).performClick() }
        device.waitForIdle()
        ins.runOnMainSync { assertEquals(MarkupTool.NONE, overlay.activeTool); overlay.getLocationOnScreen(position) }
        state("after exit"); assertPreviewAnchors(); screenshot("after-exit")
        val (x,y)=center()
        send(MotionEvent.ACTION_DOWN,x to y); send(MotionEvent.ACTION_MOVE,(x+20f) to (y+15f)); send(MotionEvent.ACTION_UP,(x+20f) to (y+15f))
        state("after post-exit pan"); assertPreviewAnchors(); screenshot("after-pan")

        // Save-as-new is temporary and restored; never overwrite the synthetic source.
        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val old = preferences.getString("edit_save_behavior", null)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun savedIds(): Set<Long> {
            val ids = mutableSetOf<Long>()
            ctx.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
                arrayOf("Glimpse_Edit_%.png"), null)?.use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
            return ids
        }
        val existing = savedIds()
        val baseToView = Matrix()
        val outputBounds = android.graphics.RectF()
        try {
            preferences.edit().putString("edit_save_behavior", "always_save_as_new").commit()
            ins.runOnMainSync {
                baseToView.set(requireNotNull(overlay.imageMatrixProvider).invoke())
                outputBounds.set(if (crop.hasUserModifiedCrop) crop.getCropOutputBounds() else crop.getImageBounds())
                trace.append("save viewBounds=$outputBounds\n")
                activity!!.findViewById<View>(R.id.saveButton).performClick()
            }
            val deadline = SystemClock.uptimeMillis() + 20000
            var added = emptySet<Long>()
            while (added.isEmpty() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(200); added = savedIds() - existing
            }
            assertEquals("Expected one saved image", 1, added.size)
            val uri = ContentUris.withAppendedId(collection, added.single()); media.add(uri)
            val bytes = requireNotNull(ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() })
            publish("touch-${name.methodName}-saved.png", "image/png", bytes)
            val saved = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                val viewToOutput = Matrix().apply { setRectToRect(outputBounds,
                    android.graphics.RectF(0f, 0f, saved.width.toFloat(), saved.height.toFloat()), Matrix.ScaleToFit.FILL) }
                val baseToOutput = Matrix().apply { setConcat(viewToOutput, baseToView) }
                val mark = floatArrayOf(240f,230f); val feature = floatArrayOf(286f,256f)
                baseToOutput.mapPoints(mark); baseToOutput.mapPoints(feature)
                trace.append("saved=${saved.width}x${saved.height} mark=${mark.toList()} feature=${feature.toList()}\n")
                assertTrue("Saved annotation detached from photo", containsColor(saved, mark[0], mark[1], Color.RED, 8))
                assertTrue("Saved photo feature missing", containsColor(saved, feature[0], feature[1], Color.CYAN, 6))
            } finally { saved.recycle() }
        } finally {
            if (old == null) preferences.edit().remove("edit_save_behavior").commit()
            else preferences.edit().putString("edit_save_behavior", old).commit()
        }
    }
}
