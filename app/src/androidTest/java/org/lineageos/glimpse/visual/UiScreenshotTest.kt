/* Emulator-only screenshot scenarios using synthetic, isolated MediaStore input.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.glimpse.ImageEditorActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.collage.CollageActivity
import org.lineageos.glimpse.ui.views.CropImageView
import java.io.File

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
        var markup = device.findObject(By.text(context.getString(R.string.editor_tab_markup)))
        if (markup == null) {
            val tabs = device.findObject(By.res(context.packageName, "categoryTabsScroll"))
            assertNotNull("Category scroll missing", tabs)
            val bounds = tabs!!.visibleBounds
            device.swipe(bounds.right - 15, bounds.centerY(), bounds.left + 15, bounds.centerY(), 25)
            markup = device.wait(Until.findObject(By.text(context.getString(R.string.editor_tab_markup))), 5000)
        }
        assertNotNull("Markup category is not visible", markup)
        markup!!.click()
        assertNotNull("Markup tools unavailable", device.wait(Until.findObject(By.desc(context.getString(R.string.editor_markup_doodle))), 10000))
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
        device.waitForIdle()
    }

    private fun collage(count: Int) {
        start(CollageActivity.createIntent(context, images.take(count)))
        val save = device.wait(Until.findObject(By.res(context.packageName, "collageSave")), 15000)
        assertNotNull("Collage rejected $count photos or did not open", save)
        assertTrue("Collage loading did not complete", device.wait(Until.hasObject(By.res(context.packageName, "collageSave").enabled(true)), 30000))
    }

    private fun capture(name: String) {
        device.waitForIdle()
        assertTrue("Screenshot failed: $name", device.takeScreenshot(File(output, "$name.png")))
        device.dumpWindowHierarchy(File(output, "$name.xml"))
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

    @Test fun markupIdle() { editor(); capture("markup-idle") }
    @Test fun markupBrush() { editor(); tool(R.string.editor_markup_doodle); capture("markup-brush") }
    @Test fun markupMosaic() { editor(); tool(R.string.editor_markup_mosaic); capture("markup-mosaic") }
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
        device.waitForIdle(); capture("markup-text-selected")
    }
    @Test fun collageTwo() { collage(2); capture("collage-two") }
    @Test fun collageSelected() {
        collage(2)
        val preview = device.findObject(By.res(context.packageName, "collagePreview"))
        assertNotNull("Collage preview missing", preview)
        val r = preview!!.visibleBounds
        device.click(r.right - r.width() / 4, r.centerY())
        capture("collage-selected")
    }
    @Test fun collageSix() { collage(6); capture("collage-six") }
}
