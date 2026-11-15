/* Exercise selection sheet through real window input and synthetic local photos.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.glimpse.MainActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ext.manageMediaPermissionDialogDismissed
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.ui.recyclerview.ThumbnailAdapter
import androidx.recyclerview.selection.SelectionTracker
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.io.File

@RunWith(AndroidJUnit4::class)
class SelectionSheetInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val images = mutableListOf<Uri>()
    private var activity: Activity? = null
    private var oldDialogPreference = false

    private fun id(name: String): Int = context.resources.getIdentifier(name, "id", context.packageName)
    private fun requiredView(name: String): View {
        val resource = id(name)
        assertTrue("Missing resource $name", resource != 0)
        return requireNotNull(activity!!.findViewById(resource))
    }

    @Before fun setUp() {
        assumeTrue("Selection sheet is candidate-only",
            InstrumentationRegistry.getArguments().getString("uiVersion") != "baseline")
        val permissions = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        oldDialogPreference = preferences.manageMediaPermissionDialogDismissed
        preferences.manageMediaPermissionDialogDismissed = true
        repeat(24) { index ->
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(40 + index * 6, 100, 180))
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "selection_fixture_$index.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GlimpseSelectionTest")
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            images.add(uri)
            try { requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Synthetic thumbnails did not load", device.wait(Until.hasObject(By.res(context.packageName, "thumbnailImageView")), 30000))
        device.waitForIdle()
    }

    @After fun tearDown() {
        device.unfreezeRotation()
        activity?.finish()
        images.forEach { context.contentResolver.delete(it, null, null) }
        if (activity != null) PreferenceManager.getDefaultSharedPreferences(context).manageMediaPermissionDialogDismissed = oldDialogPreference
    }

    private fun capture(name: String) {
        device.waitForIdle()
        val temp = File(context.cacheDir, "$name.png")
        assertTrue("Unable to capture $name", device.takeScreenshot(temp))
        val hierarchy = File(context.cacheDir, "$name.xml")
        device.dumpWindowHierarchy(hierarchy)
        listOf(temp to "image/png", hierarchy to "application/xml").forEach { (file, mime) ->
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            file.inputStream().use { input -> requireNotNull(context.contentResolver.openOutputStream(uri)).use { input.copyTo(it) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            file.delete()
        }
    }

    /** Trace only synthetic keys; never write media names, URIs, or user media identifiers. */
    @Test fun selectionStationaryLongPressTrace() {
        val trace = StringBuffer()
        fun line(message: String) { trace.append(message).append('\n') }
        fun fixtureKey(media: Media) = images.indexOf(media.uri).let { if (it < 0) "nonfixture" else "fixture-$it" }
        val grid = requiredView("recyclerView") as RecyclerView
        lateinit var tracker: SelectionTracker<Media>
        var target = ""
        var x = 0f
        var y = 0f
        lateinit var observer: SelectionTracker.SelectionObserver<Media>
        fun snapshot(stage: String) {
            instrumentation.runOnMainSync {
                val keys = tracker.selection.map(::fixtureKey).sorted()
                val gridPos = IntArray(2); grid.getLocationOnScreen(gridPos)
                val hit = grid.findChildViewUnder(x - gridPos[0], y - gridPos[1])
                val holder = hit?.let { grid.getChildViewHolder(it) }
                val hitKey = (holder as? ThumbnailAdapter.ThumbnailViewHolder)?.itemDetails?.selectionKey?.let(::fixtureKey) ?: "no-fixture"
                line("$stage hitView=${hit?.javaClass?.simpleName} position=${holder?.bindingAdapterPosition} key=$hitKey gridPaddingTop=${grid.paddingTop} gridPaddingBottom=${grid.paddingBottom}")
                val sheet = requiredView("selectionSheet")
                val parent = sheet.parent as View
                val bounds = Rect(); val visible = sheet.getGlobalVisibleRect(bounds)
                val b = BottomSheetBehavior.from(sheet)
                val lp = sheet.layoutParams as ViewGroup.MarginLayoutParams
                val insets = ViewCompat.getRootWindowInsets(sheet)?.getInsets(WindowInsetsCompat.Type.navigationBars())
                line("$stage keys=$keys count=${keys.size} sheetVisibility=${sheet.visibility} isShown=${sheet.isShown} width=${sheet.measuredWidth} height=${sheet.measuredHeight} bounds=$bounds globalVisible=$visible state=${b.state} peek=${b.peekHeight} parent=${parent.width}x${parent.height} insetBottom=${insets?.bottom} marginBottom=${lp.bottomMargin} paddingBottom=${sheet.paddingBottom} contentPaddingBottom=${requiredView("selectionSheetContent").paddingBottom}")
            }
        }
        instrumentation.runOnMainSync {
            val adapter = grid.adapter as ThumbnailAdapter
            tracker = requireNotNull(adapter.selectionTracker)
            observer = object : SelectionTracker.SelectionObserver<Media>() {
                override fun onItemStateChanged(key: Media, selected: Boolean) {
                    line("selection-change key=${fixtureKey(key)} selected=$selected keys=${tracker.selection.map(::fixtureKey).sorted()}")
                }
            }
            tracker.addObserver(observer)
            val holders = (0 until grid.childCount).mapNotNull { i ->
                grid.getChildViewHolder(grid.getChildAt(i)) as? ThumbnailAdapter.ThumbnailViewHolder
            }
            val holder = holders.first { images.contains(it.itemDetails.selectionKey?.uri) }
            val rect = Rect()
            check(holder.itemView.getGlobalVisibleRect(rect) && rect.width() == holder.itemView.width && rect.height() == holder.itemView.height)
            x = rect.exactCenterX(); y = rect.exactCenterY()
            target = fixtureKey(requireNotNull(holder.itemDetails.selectionKey))
            line("target=$target position=${holder.bindingAdapterPosition} view=${holder.itemView.javaClass.simpleName} bounds=$rect point=($x,$y)")
        }
        snapshot("before-down")
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        try {
            inject(MotionEvent.ACTION_DOWN)
            snapshot("after-down")
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250L)
            snapshot("long-press-before-up")
            inject(MotionEvent.ACTION_UP)
            device.waitForIdle()
            snapshot("after-up-and-layout")
            capture("selection-sheet-trace")
        } finally {
            instrumentation.runOnMainSync { tracker.removeObserver(observer) }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "selection-trace-long-press.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(trace.toString().toByteArray()) }
        }
        instrumentation.runOnMainSync {
            assertEquals("Stationary long press must select only its fixture key", listOf(target), tracker.selection.map(::fixtureKey).sorted())
        }
    }

    @Test fun selectionSheetOpensUpdatesExpandsAndCloses() {
        val thumbnails = device.findObjects(By.res(context.packageName, "thumbnailImageView"))
        assertTrue("Need two visible photos", thumbnails.size >= 2)
        thumbnails[0].longClick()
        assertTrue("Selection pill did not appear", device.wait(Until.hasObject(By.res(context.packageName, "selectionPill")), 10000))
        device.waitForIdle()
        // Capture even when an assertion fails so the actual interaction can be diagnosed.
        capture("selection-sheet-initial")
        instrumentation.runOnMainSync {
            val pill = requiredView("selectionPill") as ViewGroup
            val close = requiredView("selectionCancel")
            val count = requiredView("selectionCount") as TextView
            val top = requiredView("selectionTopBar")
            val sheet = requiredView("selectionSheet")
            assertTrue("Cancel and count must share the pill", close.parent === pill && count.parent === pill)
            assertEquals(context.resources.getQuantityString(R.plurals.thumbnail_selection_count, 1, 1), count.text.toString())
            assertTrue("Sheet is not a CoordinatorLayout child", sheet.parent is CoordinatorLayout)
            assertTrue("Sheet is not visible", sheet.isShown && sheet.getGlobalVisibleRect(Rect()))
            val behavior = BottomSheetBehavior.from(sheet)
            assertEquals(BottomSheetBehavior.STATE_COLLAPSED, behavior.state)
            assertTrue("Sheet peek must be positive", behavior.peekHeight > 0)
            val navInset = ViewCompat.getRootWindowInsets(sheet)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            assertEquals("Navigation inset should be applied once as the sheet margin", navInset,
                (sheet.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
            assertEquals("Sheet content must not duplicate the navigation inset", 0,
                requiredView("selectionSheetContent").paddingBottom)
            val topLocation = IntArray(2); top.getLocationOnScreen(topLocation)
            assertEquals("Selection background does not reach screen edge", 0, topLocation[1])
            val inset = ViewCompat.getRootWindowInsets(top)!!.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val pillLocation = IntArray(2); pill.getLocationOnScreen(pillLocation)
            assertTrue("Pill overlaps status icons", pillLocation[1] >= inset)
            val primary = requiredView("selectionSheetPrimary") as LinearLayout
            val collage = primary.findViewWithTag<ViewGroup>(R.id.collage)
            assertNotNull("Collage action missing", collage)
            assertTrue("Collage icon missing", (collage!!.getChildAt(0) as ImageView).drawable != null)
            assertEquals(context.getString(R.string.collage_title), (collage.getChildAt(1) as TextView).text.toString())
            val collageIcon = context.resources.getIdentifier("ic_collage", "drawable", context.packageName)
            assertTrue("Collage drawable resource missing", collageIcon != 0)
            val expected = ContextCompat.getDrawable(context, collageIcon)!!.mutate()
            val actual = (collage.getChildAt(0) as ImageView).drawable.mutate()
            val tint = MaterialColors.getColor(sheet, com.google.android.material.R.attr.colorOnSurface)
            expected.setTint(tint)
            fun pixels(drawable: android.graphics.drawable.Drawable): Bitmap {
                val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 24, 24); drawable.draw(Canvas(bitmap)); return bitmap
            }
            val expectedBitmap = pixels(expected); val actualBitmap = pixels(actual)
            assertTrue("Collage action does not use the collage drawable", expectedBitmap.sameAs(actualBitmap))
            expectedBitmap.recycle(); actualBitmap.recycle()
        }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        instrumentation.runOnMainSync {
            val surface = MaterialColors.getColor(requiredView("selectionTopBar"), com.google.android.material.R.attr.colorSurface)
            val pixel = screenshot.getPixel(screenshot.width / 2, 1)
            val difference = kotlin.math.abs(Color.red(surface) - Color.red(pixel)) +
                kotlin.math.abs(Color.green(surface) - Color.green(pixel)) +
                kotlin.math.abs(Color.blue(surface) - Color.blue(pixel))
            assertTrue("Status area does not match selection surface", difference < 45)
        }
        screenshot.recycle()
        capture("selection-sheet-one")
        val afterFirst = device.findObjects(By.res(context.packageName, "thumbnailImageView"))
        assertTrue(afterFirst.size >= 2)
        afterFirst[1].click()
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals(context.resources.getQuantityString(R.plurals.thumbnail_selection_count, 2, 2),
                (requiredView("selectionCount") as TextView).text.toString())
            assertTrue("Sheet disappeared after another selection", requiredView("selectionSheet").isShown)
        }
        capture("selection-sheet-two")
        try {
            device.setOrientationLeft(); device.waitForIdle()
            instrumentation.runOnMainSync {
                assertTrue("Sheet disappeared on rotation", requiredView("selectionSheet").isShown)
                assertEquals(context.resources.getQuantityString(R.plurals.thumbnail_selection_count, 2, 2),
                    (requiredView("selectionCount") as TextView).text.toString())
                assertTrue("Rotated peek must remain positive", BottomSheetBehavior.from(requiredView("selectionSheet")).peekHeight > 0)
            }
            capture("selection-sheet-landscape")
        } finally {
            device.setOrientationNatural(); device.waitForIdle()
        }
        val hint = device.findObject(By.res(context.packageName, "selectionSheetDragHint"))
        assertNotNull("Sheet expand control missing", hint)
        hint!!.click(); device.waitForIdle()
        val swipeArea = Rect()
        instrumentation.runOnMainSync {
            assertEquals(BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.from(requiredView("selectionSheet")).state)
            val grid = Rect(); val sheet = Rect(); val top = Rect()
            requiredView("recyclerView").getGlobalVisibleRect(grid)
            requiredView("selectionSheet").getGlobalVisibleRect(sheet)
            requiredView("selectionTopBar").getGlobalVisibleRect(top)
            swipeArea.set(grid.left, maxOf(grid.top, top.bottom), grid.right, minOf(grid.bottom, sheet.top))
            assertTrue("Grid has no interactive area above the sheet", swipeArea.height() > 80)
        }
        repeat(8) {
            device.swipe(swipeArea.centerX(), swipeArea.bottom - 20, swipeArea.centerX(), swipeArea.top + 20, 25)
            device.waitForIdle()
        }
        val lastLocation = IntArray(2)
        instrumentation.runOnMainSync {
            val grid = requiredView("recyclerView") as RecyclerView
            val last = requireNotNull(grid.findViewHolderForAdapterPosition(grid.adapter!!.itemCount - 1))
            val row = Rect(); val sheet = Rect()
            assertTrue("Last photo is not visible", last.itemView.getGlobalVisibleRect(row))
            requiredView("selectionSheet").getGlobalVisibleRect(sheet)
            assertTrue("Last photo is covered by expanded sheet", row.bottom <= sheet.top)
            lastLocation[0] = row.centerX(); lastLocation[1] = row.centerY()
        }
        device.click(lastLocation[0], lastLocation[1]); device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals(context.resources.getQuantityString(R.plurals.thumbnail_selection_count, 3, 3),
                (requiredView("selectionCount") as TextView).text.toString())
            assertEquals(BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.from(requiredView("selectionSheet")).state)
        }
        capture("selection-sheet-expanded")
        device.findObject(By.res(context.packageName, "selectionSheetDragHint"))!!.click()
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals(BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.from(requiredView("selectionSheet")).state)
            val grid = requiredView("recyclerView") as RecyclerView
            val last = requireNotNull(grid.findViewHolderForAdapterPosition(grid.adapter!!.itemCount - 1))
            val row = Rect(); val sheet = Rect()
            assertTrue("Last photo is not visible after collapse", last.itemView.getGlobalVisibleRect(row))
            requiredView("selectionSheet").getGlobalVisibleRect(sheet)
            assertTrue("Last photo is covered by collapsed sheet", row.bottom <= sheet.top)
            lastLocation[0] = row.centerX(); lastLocation[1] = row.centerY()
        }
        device.click(lastLocation[0], lastLocation[1]); device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals(context.resources.getQuantityString(R.plurals.thumbnail_selection_count, 2, 2),
                (requiredView("selectionCount") as TextView).text.toString())
            assertTrue("Sheet disappeared after deselection", requiredView("selectionSheet").isShown)
        }
        device.findObject(By.res(context.packageName, "selectionCancel"))!!.click()
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertTrue("Sheet still visible after cancel", !requiredView("selectionSheet").isShown)
            assertTrue("Selection toolbar still visible after cancel", !requiredView("selectionTopBar").isShown)
        }
        capture("selection-sheet-closed")
    }
}
