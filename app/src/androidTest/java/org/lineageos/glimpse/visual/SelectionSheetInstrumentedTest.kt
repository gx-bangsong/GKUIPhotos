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
        // Store screenshots as generic downloads so the gallery under test does
        // not ingest its own evidence and reorder the active selection grid.
        listOf(temp to "application/octet-stream", hierarchy to "application/xml").forEach { (file, mime) ->
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

    private fun waitForSheetState(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var state = -1
        do {
            instrumentation.runOnMainSync { state = BottomSheetBehavior.from(requiredView("selectionSheet")).state }
            if (state == expected) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Sheet did not reach requested state", expected, state)
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
        var hitBeforeUp = ""
        lateinit var observer: SelectionTracker.SelectionObserver<Media>
        fun snapshot(stage: String) {
            instrumentation.runOnMainSync {
                val keys = tracker.selection.map(::fixtureKey).sorted()
                val gridPos = IntArray(2); grid.getLocationOnScreen(gridPos)
                val hit = grid.findChildViewUnder(x - gridPos[0], y - gridPos[1])
                val holder = hit?.let { grid.getChildViewHolder(it) }
                val hitKey = (holder as? ThumbnailAdapter.ThumbnailViewHolder)?.itemDetails?.selectionKey?.let(::fixtureKey) ?: "no-fixture"
                if (stage == "long-press-before-up") hitBeforeUp = hitKey
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
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = 1f
            })
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
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
            // The tracker and this observer are discarded when the test activity is destroyed.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "selection-trace-long-press.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(trace.toString().toByteArray()) }
        }
        instrumentation.runOnMainSync {
            assertEquals("Grid moved under the initiating finger", target, hitBeforeUp)
            assertEquals("Stationary long press must select only its fixture key", listOf(target), tracker.selection.map(::fixtureKey).sorted())
            val sheet = requiredView("selectionSheet")
            assertTrue("Sheet is not visible after initial layout", sheet.isShown && sheet.getGlobalVisibleRect(Rect()))
            assertEquals(BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.from(sheet).state)
            assertTrue("Sheet peek is zero after layout", BottomSheetBehavior.from(sheet).peekHeight > 0)
        }
    }

    @Test fun selectionUiAutomatorLongClickSelectsOnlyItsTarget() {
        val grid = requiredView("recyclerView") as RecyclerView
        lateinit var expected: Media
        lateinit var tracker: SelectionTracker<Media>
        val bounds = Rect()
        instrumentation.runOnMainSync {
            tracker = requireNotNull((grid.adapter as ThumbnailAdapter).selectionTracker)
            val holder = (0 until grid.childCount).mapNotNull { i ->
                grid.getChildViewHolder(grid.getChildAt(i)) as? ThumbnailAdapter.ThumbnailViewHolder
            }.first { images.contains(it.itemDetails.selectionKey?.uri) }
            expected = requireNotNull(holder.itemDetails.selectionKey)
            assertTrue(holder.itemView.getGlobalVisibleRect(bounds))
        }
        val thumbnail = device.findObjects(By.res(context.packageName, "thumbnailImageView"))
            .first { bounds.contains(it.visibleCenter.x, it.visibleCenter.y) }
        thumbnail.longClick()
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals("Long click selected unexpected keys", listOf(expected), tracker.selection.toList())
            assertTrue("Long click opened a photo or hid the selection sheet", requiredView("selectionSheet").isShown)
        }
    }

    @Test fun selectionSheetNavigationInsets() {
        val overlays = device.executeShellCommand("cmd overlay list --user current")
        val enabled = overlays.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("[x]") && it.contains("com.android.internal.systemui.navbar.") }
            ?.substringAfter("[x]")?.trim()
        assertNotNull("Cannot determine original navigation overlay", enabled)
        val modes = listOf("gestural" to "com.android.internal.systemui.navbar.gestural",
            "three-button" to "com.android.internal.systemui.navbar.threebutton")
        try {
            for ((mode, overlay) in modes) {
                assertTrue("Navigation overlay missing: $mode", overlays.contains(overlay))
                device.executeShellCommand("cmd overlay enable-exclusive --category --user current $overlay")
                device.waitForIdle(); Thread.sleep(500)
                val current = device.executeShellCommand("cmd overlay list --user current")
                assertTrue("Navigation overlay not enabled: $mode", current.lineSequence().any { it.trim() == "[x] $overlay" })
                // Changing system navigation may recreate the activity and its grid.
                // Start a fresh activity, then wait for the synthetic fixtures to bind.
                activity?.finish()
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                assertTrue("Grid did not appear after switching to $mode", device.wait(Until.hasObject(By.res(context.packageName, "thumbnailImageView")), 30000))
                val grid = requiredView("recyclerView") as RecyclerView
                val deadline = SystemClock.uptimeMillis() + 10000
                var fixtureVisible = false
                do {
                    instrumentation.runOnMainSync {
                        fixtureVisible = (0 until grid.childCount).any { i ->
                            (grid.getChildViewHolder(grid.getChildAt(i)) as? ThumbnailAdapter.ThumbnailViewHolder)
                                ?.itemDetails?.selectionKey?.uri in images
                        }
                    }
                    if (!fixtureVisible) SystemClock.sleep(100)
                } while (!fixtureVisible && SystemClock.uptimeMillis() < deadline)
                assertTrue("Synthetic fixture did not bind after switching to $mode", fixtureVisible)
                var x = 0f; var y = 0f
                instrumentation.runOnMainSync {
                    val holder = (0 until grid.childCount).mapNotNull { i ->
                        grid.getChildViewHolder(grid.getChildAt(i)) as? ThumbnailAdapter.ThumbnailViewHolder
                    }.first { images.contains(it.itemDetails.selectionKey?.uri) }
                    val rect = Rect(); assertTrue(holder.itemView.getGlobalVisibleRect(rect))
                    x = rect.exactCenterX(); y = rect.exactCenterY()
                }
                val down = SystemClock.uptimeMillis()
                fun inject(action: Int) {
                    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                    val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, properties, coords,
                        0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN); Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250L)
                inject(MotionEvent.ACTION_UP); device.waitForIdle()
                capture("selection-sheet-nav-$mode")
                instrumentation.runOnMainSync {
                    val sheet = requiredView("selectionSheet")
                    val nav = requireNotNull(ViewCompat.getRootWindowInsets(sheet)).getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    val rect = Rect()
                    assertTrue("$mode sheet not visible", sheet.isShown && sheet.getGlobalVisibleRect(rect))
                    assertEquals("$mode sheet margin duplicates inset", 0, (sheet.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
                    assertEquals("$mode content does not own inset", nav, requiredView("selectionSheetContent").paddingBottom)
                    val behavior = BottomSheetBehavior.from(sheet)
                    assertEquals(BottomSheetBehavior.STATE_COLLAPSED, behavior.state)
                    assertTrue("$mode peek is zero", behavior.peekHeight > 0)
                    val root = sheet.rootView; val rootPos = IntArray(2); root.getLocationOnScreen(rootPos)
                    val safeBottom = rootPos[1] + root.height - nav
                    val parent = sheet.parent as View; val parentPos = IntArray(2); parent.getLocationOnScreen(parentPos)
                    val primary = requiredView("selectionSheetPrimary") as ViewGroup
                    val children = (0 until primary.childCount).map { primary.getChildAt(it) }
                    val diagnostics = StringBuilder("mode=$mode root=${rootPos.contentToString()} ${root.width}x${root.height} coordinator=${parentPos.contentToString()} ${parent.width}x${parent.height} sheet=$rect state=${behavior.state} peek=${behavior.peekHeight} inset=$nav safeBottom=$safeBottom margin=${(sheet.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin} contentPadding=${requiredView("selectionSheetContent").paddingBottom}\n")
                    children.forEachIndexed { i, child ->
                        val pos = IntArray(2); child.getLocationOnScreen(pos); val visible = Rect()
                        val shown = child.getGlobalVisibleRect(visible)
                        diagnostics.append("action=$i origin=${pos.contentToString()} size=${child.width}x${child.height} shown=$shown visible=$visible\n")
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "selection-trace-nav-$mode.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
                    }
                    val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(diagnostics.toString().toByteArray()) }
                    children.forEachIndexed { i, child ->
                        val pos = IntArray(2); child.getLocationOnScreen(pos); val visible = Rect()
                        assertTrue("$mode action $i not fully visible", child.getGlobalVisibleRect(visible) &&
                            visible == Rect(pos[0], pos[1], pos[0] + child.width, pos[1] + child.height))
                        assertTrue("$mode action $i overlaps navigation", visible.bottom <= safeBottom)
                    }
                }
                device.findObject(By.res(context.packageName, "selectionSheetDragHint"))!!.click(); device.waitForIdle()
                waitForSheetState(BottomSheetBehavior.STATE_EXPANDED)
                capture("selection-sheet-nav-$mode-expanded")
                instrumentation.runOnMainSync {
                    val sheet = requiredView("selectionSheet")
                    assertEquals(BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.from(sheet).state)
                    val root = sheet.rootView; val rootPos = IntArray(2); root.getLocationOnScreen(rootPos)
                    val nav = requireNotNull(ViewCompat.getRootWindowInsets(sheet)).getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    listOf("selectionSheetPrimary", "selectionSheetMore").map(::requiredView).filter { it.isShown }.forEach { row ->
                        val bounds = Rect(); val pos = IntArray(2); row.getLocationOnScreen(pos)
                        assertTrue("$mode expanded row not fully visible", row.getGlobalVisibleRect(bounds) && bounds == Rect(pos[0], pos[1], pos[0] + row.width, pos[1] + row.height))
                        assertTrue("$mode expanded row overlaps navigation", bounds.bottom <= rootPos[1] + root.height - nav)
                    }
                }
                device.findObject(By.res(context.packageName, "selectionSheetDragHint"))!!.click(); device.waitForIdle()
                waitForSheetState(BottomSheetBehavior.STATE_COLLAPSED)
                device.findObject(By.res(context.packageName, "selectionCancel"))!!.click(); device.waitForIdle()
            }
        } finally {
            if (enabled != null) device.executeShellCommand("cmd overlay enable-exclusive --category --user current $enabled")
            device.waitForIdle()
        }
    }

    @Test fun selectionSheetOpensUpdatesExpandsAndCloses() {
        val grid = requiredView("recyclerView") as RecyclerView
        lateinit var tracker: SelectionTracker<Media>
        lateinit var firstKey: Media
        var firstX = 0f; var firstY = 0f
        instrumentation.runOnMainSync {
            tracker = requireNotNull((grid.adapter as ThumbnailAdapter).selectionTracker)
            val holder = (0 until grid.childCount).mapNotNull { i ->
                grid.getChildViewHolder(grid.getChildAt(i)) as? ThumbnailAdapter.ThumbnailViewHolder
            }.first { images.contains(it.itemDetails.selectionKey?.uri) }
            firstKey = requireNotNull(holder.itemDetails.selectionKey)
            val bounds = Rect()
            assertTrue("Fixture must be fully visible", holder.itemView.getGlobalVisibleRect(bounds) &&
                bounds.width() == holder.itemView.width && bounds.height() == holder.itemView.height)
            firstX = bounds.exactCenterX(); firstY = bounds.exactCenterY()
            assertEquals("Selection not initially empty", 0, tracker.selection.count())
        }
        val downTime = SystemClock.uptimeMillis()
        fun finger(action: Int) {
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = firstX; y = firstY; pressure = 1f; size = 1f })
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        finger(MotionEvent.ACTION_DOWN)
        Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250L)
        finger(MotionEvent.ACTION_UP)
        assertTrue("Selection pill did not appear", device.wait(Until.hasObject(By.res(context.packageName, "selectionPill")), 10000))
        instrumentation.runOnMainSync { assertEquals("Wrong first selection key", listOf(firstKey), tracker.selection.toList()) }
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
            assertEquals("Sheet margin must not duplicate the navigation inset", 0,
                (sheet.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
            assertEquals("Sheet content owns the navigation inset", navInset,
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
            val iconView = collage.getChildAt(0) as ImageView
            assertTrue("Collage icon is not visible", iconView.isShown && iconView.getGlobalVisibleRect(Rect()))
            val menu = android.widget.PopupMenu(sheet.context, sheet).apply { inflate(R.menu.fragment_album_action_bar) }.menu
            val menuItem = requireNotNull(menu.findItem(R.id.collage))
            assertNotNull("Collage menu icon missing", menuItem.icon)
            assertEquals(context.getString(R.string.collage_title), menuItem.title.toString())
            val expected = ContextCompat.getDrawable(iconView.context, collageIcon)!!.mutate()
            val actual = iconView.drawable.mutate()
            val tint = MaterialColors.getColor(sheet, com.google.android.material.R.attr.colorOnSurface)
            assertEquals("Collage icon tint does not match theme", tint, iconView.imageTintList!!.defaultColor)
            expected.state = actual.state
            expected.setTint(tint)
            fun pixels(drawable: android.graphics.drawable.Drawable): Bitmap {
                val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 24, 24); drawable.draw(Canvas(bitmap)); return bitmap
            }
            val expectedBitmap = pixels(expected); val actualBitmap = pixels(actual)
            assertTrue("Collage drawable rendered empty", (0 until actualBitmap.width).any { x ->
                (0 until actualBitmap.height).any { y -> Color.alpha(actualBitmap.getPixel(x, y)) > 0 }
            })
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
        lateinit var secondKey: Media
        var secondX = 0; var secondY = 0
        instrumentation.runOnMainSync {
            val topBounds = Rect(); val sheetBounds = Rect()
            assertTrue(requiredView("selectionTopBar").getGlobalVisibleRect(topBounds))
            assertTrue(requiredView("selectionSheet").getGlobalVisibleRect(sheetBounds))
            val holder = (0 until grid.childCount).mapNotNull { i ->
                grid.getChildViewHolder(grid.getChildAt(i)) as? ThumbnailAdapter.ThumbnailViewHolder
            }.first {
                val bounds = Rect()
                it.itemDetails.selectionKey?.let { key -> key != firstKey && images.contains(key.uri) } == true &&
                    it.itemView.getGlobalVisibleRect(bounds) && bounds.width() == it.itemView.width && bounds.height() == it.itemView.height &&
                    bounds.top >= topBounds.bottom && bounds.bottom <= sheetBounds.top
            }
            secondKey = requireNotNull(holder.itemDetails.selectionKey)
            val bounds = Rect()
            assertTrue("Second fixture must be fully visible", holder.itemView.getGlobalVisibleRect(bounds) &&
                bounds.width() == holder.itemView.width && bounds.height() == holder.itemView.height)
            secondX = bounds.centerX(); secondY = bounds.centerY()
        }
        assertTrue("Second fixture tap failed", device.click(secondX, secondY))
        device.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals("Wrong two selection keys", setOf(firstKey, secondKey), tracker.selection.toSet())
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
        waitForSheetState(BottomSheetBehavior.STATE_EXPANDED)
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
        capture("selection-sheet-expanded-scroll")
        instrumentation.runOnMainSync {
            val scrollGrid = requiredView("recyclerView") as RecyclerView
            val sheet = requiredView("selectionSheet")
            val gridPos = IntArray(2); val sheetPos = IntArray(2)
            scrollGrid.getLocationOnScreen(gridPos); sheet.getLocationOnScreen(sheetPos)
            val last = scrollGrid.findViewHolderForAdapterPosition(scrollGrid.adapter!!.itemCount - 1)?.itemView
            val lastPos = IntArray(2); last?.getLocationOnScreen(lastPos)
            val gridVisible = Rect(); val sheetVisible = Rect(); val lastVisible = Rect()
            scrollGrid.getGlobalVisibleRect(gridVisible); sheet.getGlobalVisibleRect(sheetVisible)
            val lastShown = last?.getGlobalVisibleRect(lastVisible) ?: false
            val trace = "gridPos=${gridPos.contentToString()} gridSize=${scrollGrid.width}x${scrollGrid.height} gridVisible=$gridVisible paddingTop=${scrollGrid.paddingTop} paddingBottom=${scrollGrid.paddingBottom} canScrollDown=${scrollGrid.canScrollVertically(1)} sheetPos=${sheetPos.contentToString()} sheetVisible=$sheetVisible lastPos=${lastPos.contentToString()} lastSize=${last?.width}x${last?.height} lastVisible=$lastVisible lastShown=$lastShown\n"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "selection-trace-grid-scroll.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GlimpseUiTest")
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(trace.toByteArray()) }
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
        waitForSheetState(BottomSheetBehavior.STATE_COLLAPSED)
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
            assertTrue("Ordinary navigation not restored", requiredView("navigationBarView").isShown)
        }
        capture("selection-sheet-closed")
    }
}
