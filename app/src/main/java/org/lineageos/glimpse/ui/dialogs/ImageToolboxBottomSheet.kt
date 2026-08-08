/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.dialogs

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.ImageView
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
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.utils.media.CompressOptions
import org.lineageos.glimpse.utils.media.ImageTargetFormat
import org.lineageos.glimpse.utils.media.ImageToolbox

/**
 * Module 6: an offline image/video toolkit (compress, convert format, video to
 * GIF). New HCI: for video->GIF we now expose a modern Material3 UI with
 * preview thumbnail, duration Slider, FPS chips, quality chips and live estimate.
 */
class ImageToolboxBottomSheet(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val media: Media,
) : BottomSheetDialog(context) {

    private val isVideo = media.mediaType == MediaType.VIDEO

    private val compressSection by lazy { findViewById<android.view.View>(R.id.compressSection)!! }
    private val convertSection by lazy { findViewById<android.view.View>(R.id.convertSection)!! }
    private val videoToGifSection by lazy { findViewById<android.view.View>(R.id.videoToGifSection)!! }
    private val compressRadioGroup by lazy { findViewById<RadioGroup>(R.id.compressRadioGroup)!! }
    private val convertChipGroup by lazy { findViewById<ChipGroup>(R.id.convertChipGroup)!! }
    private val progressBar by lazy { findViewById<LinearProgressIndicator>(R.id.toolboxProgressBar)!! }

    // New HCI for video->GIF
    private val previewImage by lazy { findViewById<ImageView>(R.id.videoGifPreview) }
    private val estimateText by lazy { findViewById<TextView>(R.id.videoGifEstimate) }
    private val durationSlider by lazy { findViewById<Slider>(R.id.gifDurationSlider) }
    private val durationValue by lazy { findViewById<TextView>(R.id.gifDurationValue) }
    private val fpsChipGroup by lazy { findViewById<ChipGroup>(R.id.fpsChipGroup) }
    private val qualityChipGroup by lazy { findViewById<ChipGroup>(R.id.qualityChipGroup) }
    // Legacy seekbar kept for compat but hidden
    private val legacySeekBar by lazy { findViewById<SeekBar>(R.id.gifDurationSeekBar) }

    private var convertTarget: ImageTargetFormat = ImageTargetFormat.JPEG
    private var selectedFps: Int = 8
    private var selectedMaxEdge: Int = 480

    init {
        setContentView(R.layout.dialog_image_toolbox)

        // Only images expose compress + convert; only videos expose video→GIF.
        compressSection.isVisible = !isVideo
        convertSection.isVisible = !isVideo
        videoToGifSection.isVisible = isVideo

        setupConvertChips()
        if (isVideo) {
            setupVideoToGifModernUi()
            loadVideoPreview()
        }

        findViewById<MaterialButton>(R.id.compressRunButton)!!.setOnClickListener {
            runCompress()
        }
        findViewById<MaterialButton>(R.id.convertRunButton)!!.setOnClickListener {
            runConvert()
        }
        findViewById<MaterialButton>(R.id.videoToGifRunButton)!!.setOnClickListener {
            runVideoToGifModern()
        }
    }

    private fun setupConvertChips() {
        val targets = listOf(ImageTargetFormat.JPEG, ImageTargetFormat.PNG, ImageTargetFormat.WEBP)
        targets.forEachIndexed { index, target ->
            val chip = Chip(context).apply {
                text = target.name
                isCheckable = true
                id = index
                isChecked = index == 0
            }
            chip.setOnClickListener { convertTarget = target }
            convertChipGroup.addView(chip)
        }
    }

    private fun setupVideoToGifModernUi() {
        // Duration slider 1..15
        durationSlider?.let { slider ->
            slider.value = 5f
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val seconds = value.toInt().coerceAtLeast(1)
                    durationValue?.text = "${seconds}s"
                    legacySeekBar?.progress = seconds
                    updateEstimate()
                }
            }
            durationValue?.text = "${slider.value.toInt()}s"
        }

        // FPS chips: 5, 8, 10, 12, 15
        val fpsOptions = listOf(5 to "5 fps", 8 to "8 fps", 10 to "10 fps", 12 to "12 fps", 15 to "15 fps")
        fpsChipGroup?.let { group ->
            group.removeAllViews()
            fpsOptions.forEach { (fps, label) ->
                val chip = Chip(context).apply {
                    text = label
                    isCheckable = true
                    isChecked = fps == selectedFps
                    tag = fps
                }
                chip.setOnClickListener {
                    selectedFps = fps
                    // Uncheck others handled by singleSelection, but ensure value
                    updateEstimate()
                }
                group.addView(chip)
            }
            // ChipGroup singleSelection will manage, but set initial checked
            (group.getChildAt(1) as? Chip)?.isChecked = true // 8fps default
        }

        // Quality chips: map to maxEdge
        val qualityOptions = listOf(
            240 to "240p",
            360 to "360p",
            480 to "480p",
            720 to "720p"
        )
        qualityChipGroup?.let { group ->
            group.removeAllViews()
            qualityOptions.forEach { (edge, label) ->
                val chip = Chip(context).apply {
                    text = label
                    isCheckable = true
                    isChecked = edge == selectedMaxEdge
                    tag = edge
                }
                chip.setOnClickListener {
                    selectedMaxEdge = edge
                    updateEstimate()
                }
                group.addView(chip)
            }
        }

        // Sync legacy seekbar to slider for old code path
        legacySeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    durationSlider?.value = progress.coerceAtLeast(1).toFloat()
                    durationValue?.text = "${progress.coerceAtLeast(1)}s"
                    updateEstimate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateEstimate()
    }

    private fun loadVideoPreview() {
        // Load first frame as thumbnail, off main thread
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, media.uri)
                bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    previewImage?.setImageBitmap(bmp)
                } else {
                    previewImage?.setImageResource(R.drawable.ic_video_to_gif)
                }
            }
        }
    }

    private fun updateEstimate() {
        val seconds = (durationSlider?.value?.toInt() ?: legacySeekBar?.progress ?: 5).coerceAtLeast(1)
        val fps = selectedFps
        val edge = selectedMaxEdge
        val frames = (seconds * fps).coerceAtMost(36)
        estimateText?.text = "~${seconds}s • ${fps}fps • ${edge}p • ~${frames} frames"
    }

    private fun runCompress() {
        val options = when (compressRadioGroup.checkedRadioButtonId) {
            R.id.compressHigh -> CompressOptions(quality = 85)
            R.id.compressMedium -> CompressOptions(quality = 70)
            R.id.compressLow -> CompressOptions(quality = 50)
            R.id.compressMaxSize -> CompressOptions(maxBytes = 500 * 1024L)
            else -> CompressOptions(quality = 70)
        }
        launchHeavy {
            ImageToolbox.compress(context, media.uri, options)
        }
    }

    private fun runConvert() {
        launchHeavy {
            ImageToolbox.convert(context, media.uri, convertTarget)
        }
    }

    // New modern path using selected fps / quality / duration
    private fun runVideoToGifModern() {
        val seconds = (durationSlider?.value?.toInt() ?: legacySeekBar?.progress ?: 5).coerceAtLeast(1)
        val fps = selectedFps
        val maxEdge = selectedMaxEdge
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.videoToGif(
                context,
                media.uri,
                durationSeconds = seconds,
                fps = fps,
                maxEdge = maxEdge
            ) { p ->
                mainHandler.post { onProgress(p) }
            }
        }
    }

    /**
     * Run a toolbox operation off the main thread, showing a progress bar and a
     * result toast. `progressive` switches to a determinate bar updated via the
     * optional progress callback (used by video→GIF).
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
                Toast.makeText(context, R.string.toolbox_done, Toast.LENGTH_LONG).show()
            }.onFailure {
                progressBar.isVisible = false
                android.util.Log.e(
                    "GlimpseToolbox", "toolbox operation failed", it
                )
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
