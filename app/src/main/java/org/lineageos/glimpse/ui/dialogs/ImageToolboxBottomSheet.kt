/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.dialogs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.utils.media.CompressOptions
import org.lineageos.glimpse.utils.media.ImageFilter
import org.lineageos.glimpse.utils.media.ImageTargetFormat
import org.lineageos.glimpse.utils.media.ImageToolbox

/**
 * Module 6 & 8: an offline single- and multi-image/video toolkit (compress, convert
 * format, batch edit, video to GIF). All heavy work runs on background dispatchers;
 * a progress bar keeps the UI responsive. No network access is performed.
 */
class ImageToolboxBottomSheet(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val medias: List<Media>,
    private val onComplete: (() -> Unit)? = null,
) : BottomSheetDialog(context) {

    constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        media: Media,
    ) : this(context, lifecycleOwner, listOf(media), null)

    private val isSingleVideo = medias.size == 1 && medias.first().mediaType == MediaType.VIDEO
    private val isMulti = medias.size > 1
    private val hasImages = medias.any { it.mediaType == MediaType.IMAGE }

    private val titleTextView by lazy { findViewById<TextView>(R.id.toolboxTitleTextView)!! }
    private val compressSection by lazy { findViewById<View>(R.id.compressSection)!! }
    private val convertSection by lazy { findViewById<View>(R.id.convertSection)!! }
    private val editSection by lazy { findViewById<View>(R.id.editSection)!! }
    private val videoToGifSection by lazy { findViewById<View>(R.id.videoToGifSection)!! }

    private val compressRadioGroup by lazy { findViewById<RadioGroup>(R.id.compressRadioGroup)!! }
    private val convertChipGroup by lazy { findViewById<ChipGroup>(R.id.convertChipGroup)!! }
    private val rotateChipGroup by lazy { findViewById<ChipGroup>(R.id.rotateChipGroup)!! }
    private val filterChipGroup by lazy { findViewById<ChipGroup>(R.id.filterChipGroup)!! }
    private val gifDurationSeekBar by lazy { findViewById<SeekBar>(R.id.gifDurationSeekBar)!! }
    private val progressBar by lazy { findViewById<LinearProgressIndicator>(R.id.toolboxProgressBar)!! }

    private var convertTarget: ImageTargetFormat = ImageTargetFormat.JPEG
    private var selectedRotation: Int = 0
    private var selectedFilter: ImageFilter = ImageFilter.NONE

    init {
        setContentView(R.layout.dialog_image_toolbox)

        titleTextView.text = if (isMulti) {
            context.getString(R.string.toolbox_title_multi, medias.size)
        } else {
            context.getString(R.string.toolbox_title)
        }

        // Only images expose compress + convert + edit; only videos expose video→GIF.
        compressSection.isVisible = hasImages
        convertSection.isVisible = hasImages
        editSection.isVisible = hasImages
        videoToGifSection.isVisible = isSingleVideo

        if (hasImages) {
            setupConvertChips()
            setupRotateChips()
            setupFilterChips()
        }

        findViewById<MaterialButton>(R.id.compressRunButton)!!.setOnClickListener {
            runCompress()
        }
        findViewById<MaterialButton>(R.id.convertRunButton)!!.setOnClickListener {
            runConvert()
        }
        findViewById<MaterialButton>(R.id.editRunButton)!!.setOnClickListener {
            runEdit()
        }
        findViewById<MaterialButton>(R.id.videoToGifRunButton)!!.setOnClickListener {
            runVideoToGif()
        }
    }

    private fun setupConvertChips() {
        val targets = listOf(ImageTargetFormat.JPEG, ImageTargetFormat.PNG, ImageTargetFormat.WEBP)
        targets.forEachIndexed { index, target ->
            val chip = Chip(context).apply {
                text = target.name
                isCheckable = true
                id = View.generateViewId()
                isChecked = index == 0
            }
            chip.setOnClickListener {
                convertTarget = target
                convertChipGroup.check(chip.id)
            }
            convertChipGroup.addView(chip)
        }
    }

    private fun setupRotateChips() {
        val rotations = listOf(0 to "0°", 90 to "+90°", 180 to "+180°", 270 to "+270°")
        rotations.forEachIndexed { index, (deg, label) ->
            val chip = Chip(context).apply {
                text = label
                isCheckable = true
                id = View.generateViewId()
                isChecked = index == 0
            }
            chip.setOnClickListener {
                selectedRotation = deg
                rotateChipGroup.check(chip.id)
            }
            rotateChipGroup.addView(chip)
        }
    }

    private fun setupFilterChips() {
        val filters = listOf(
            ImageFilter.NONE to R.string.editor_filter_none,
            ImageFilter.GRAYSCALE to R.string.editor_filter_grayscale,
            ImageFilter.SEPIA to R.string.editor_filter_sepia,
            ImageFilter.INVERT to R.string.editor_filter_invert,
            ImageFilter.WARM to R.string.editor_filter_warm,
            ImageFilter.COOL to R.string.editor_filter_cool,
        )
        filters.forEachIndexed { index, (filter, labelRes) ->
            val chip = Chip(context).apply {
                text = context.getString(labelRes)
                isCheckable = true
                id = View.generateViewId()
                isChecked = index == 0
            }
            chip.setOnClickListener {
                selectedFilter = filter
                filterChipGroup.check(chip.id)
            }
            filterChipGroup.addView(chip)
        }
    }

    private fun runCompress() {
        val options = when (compressRadioGroup.checkedRadioButtonId) {
            R.id.compressHigh -> CompressOptions(quality = 85)
            R.id.compressMedium -> CompressOptions(quality = 70)
            R.id.compressLow -> CompressOptions(quality = 50)
            R.id.compressMaxSize -> CompressOptions(maxBytes = 500 * 1024L)
            else -> CompressOptions(quality = 70)
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val imageUris = medias.filter { it.mediaType == MediaType.IMAGE }.map { it.uri }
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.batchCompress(context, imageUris, options) { current, total ->
                mainHandler.post { onProgress(current.toFloat() / total.toFloat()) }
            }
        }
    }

    private fun runConvert() {
        val mainHandler = Handler(Looper.getMainLooper())
        val imageUris = medias.filter { it.mediaType == MediaType.IMAGE }.map { it.uri }
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.batchConvert(context, imageUris, convertTarget) { current, total ->
                mainHandler.post { onProgress(current.toFloat() / total.toFloat()) }
            }
        }
    }

    private fun runEdit() {
        val mainHandler = Handler(Looper.getMainLooper())
        val imageUris = medias.filter { it.mediaType == MediaType.IMAGE }.map { it.uri }
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.batchEdit(context, imageUris, selectedRotation, selectedFilter) { current, total ->
                mainHandler.post { onProgress(current.toFloat() / total.toFloat()) }
            }
        }
    }

    private fun runVideoToGif() {
        val videoMedia = medias.firstOrNull { it.mediaType == MediaType.VIDEO } ?: return
        val seconds = gifDurationSeekBar.progress.coerceAtLeast(1)
        val mainHandler = Handler(Looper.getMainLooper())
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.videoToGif(context, videoMedia.uri, durationSeconds = seconds) { p ->
                mainHandler.post { onProgress(p) }
            }
        }
    }

    /**
     * Run a toolbox operation off the main thread, showing a progress bar and a
     * result toast. `progressive` switches to a determinate bar updated via the
     * optional progress callback.
     */
    private fun launchHeavy(progressive: Boolean = false, block: suspend ((Float) -> Unit) -> Unit) {
        progressBar.isVisible = true
        progressBar.isIndeterminate = !progressive
        lifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    if (progressive) {
                        block { p ->
                            progressBar.isIndeterminate = false
                            progressBar.progress = (p * 100).toInt()
                        }
                    } else {
                        block {}
                    }
                }
            }.onSuccess {
                progressBar.isVisible = false
                dismiss()
                val count = medias.size
                val msg = if (count > 1) {
                    context.getString(R.string.toolbox_done_multi, count)
                } else {
                    context.getString(R.string.toolbox_done)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                onComplete?.invoke()
            }.onFailure {
                progressBar.isVisible = false
                android.util.Log.e("GlimpseToolbox", "toolbox operation failed", it)
                Toast.makeText(
                    context,
                    context.getString(R.string.toolbox_failed) +
                        ": " + (it.message ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
