/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.dialogs

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.View
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
import org.lineageos.glimpse.IdPhotoActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.utils.media.CompressOptions
import org.lineageos.glimpse.utils.media.ImageFilter
import org.lineageos.glimpse.utils.media.ImageTargetFormat
import org.lineageos.glimpse.utils.media.ImageToolbox

/**
 * Module 6 & 8: an offline single- and multi-image/video toolkit (compress, convert
 * format, batch edit, video to GIF, export to PDF). All heavy work runs on background
 * dispatchers; a progress bar keeps the UI responsive. No network access is performed.
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
    private val idPhotoSection by lazy { findViewById<View>(R.id.idPhotoSection)!! }
    private val videoToGifSection by lazy { findViewById<View>(R.id.videoToGifSection)!! }

    // Compress controls
    private val compressResolutionChipGroup by lazy { findViewById<ChipGroup>(R.id.compressResolutionChipGroup) }
    private val compressScaleSlider by lazy { findViewById<Slider>(R.id.compressScaleSlider) }
    private val compressScaleValue by lazy { findViewById<TextView>(R.id.compressScaleValue) }
    private val compressScaleChipGroup by lazy { findViewById<ChipGroup>(R.id.compressScaleChipGroup) }
    private val compressQualityChipGroup by lazy { findViewById<ChipGroup>(R.id.compressQualityChipGroup) }
    private val compressSizeChipGroup by lazy { findViewById<ChipGroup>(R.id.compressSizeChipGroup) }
    private val compressRadioGroup by lazy { findViewById<RadioGroup>(R.id.compressRadioGroup) }

    // Convert & Edit controls
    private val convertChipGroup by lazy { findViewById<ChipGroup>(R.id.convertChipGroup)!! }
    private val rotateChipGroup by lazy { findViewById<ChipGroup>(R.id.rotateChipGroup)!! }
    private val filterChipGroup by lazy { findViewById<ChipGroup>(R.id.filterChipGroup)!! }
    private val idPhotoOpenButton by lazy { findViewById<MaterialButton>(R.id.idPhotoOpenButton)!! }

    // Modern Video to GIF controls
    private val previewImage by lazy { findViewById<ImageView>(R.id.videoGifPreview) }
    private val estimateText by lazy { findViewById<TextView>(R.id.videoGifEstimate) }
    private val durationSlider by lazy { findViewById<Slider>(R.id.gifDurationSlider) }
    private val durationValue by lazy { findViewById<TextView>(R.id.gifDurationValue) }
    private val fpsChipGroup by lazy { findViewById<ChipGroup>(R.id.fpsChipGroup) }
    private val qualityChipGroup by lazy { findViewById<ChipGroup>(R.id.qualityChipGroup) }
    private val legacySeekBar by lazy { findViewById<SeekBar>(R.id.gifDurationSeekBar) }

    private val progressBar by lazy { findViewById<LinearProgressIndicator>(R.id.toolboxProgressBar)!! }

    private var selectedResolutionMaxEdge: Int? = null
    private var selectedScaleRatio: Float = 1.0f
    private var selectedQuality: Int = 85
    private var selectedMaxBytes: Long? = null

    private var convertTarget: ImageTargetFormat = ImageTargetFormat.JPEG
    private var selectedRotation: Int = 0
    private var selectedFilter: ImageFilter = ImageFilter.NONE

    private var selectedFps: Int = 8
    private var selectedMaxEdge: Int = 480

    init {
        setContentView(R.layout.dialog_image_toolbox)

        titleTextView.text = if (isMulti) {
            context.getString(R.string.toolbox_title_multi, medias.size)
        } else {
            context.getString(R.string.toolbox_title)
        }

        // Only images expose compress + convert + edit; only videos expose video→GIF.
        val isSingleImage = medias.size == 1 && medias.first().mediaType == MediaType.IMAGE
        compressSection.isVisible = hasImages
        convertSection.isVisible = hasImages
        editSection.isVisible = hasImages
        idPhotoSection.isVisible = isSingleImage
        videoToGifSection.isVisible = isSingleVideo

        if (hasImages) {
            setupCompressControls()
            setupConvertChips()
            setupRotateChips()
            setupFilterChips()
        }

        if (isSingleImage) {
            idPhotoOpenButton.setOnClickListener {
                dismiss()
                val media = medias.first()
                val intent = IdPhotoActivity.createIntent(context, media.uri, media.mimeType)
                context.startActivity(intent)
                onComplete?.invoke()
            }
        }

        if (isSingleVideo) {
            setupVideoToGifModernUi()
            loadVideoPreview()
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
            runVideoToGifModern()
        }
    }

    private fun setupCompressControls() {
        // 1. Resolution preset chips
        val resolutionPresets = listOf(
            null to R.string.toolbox_compress_res_original,
            3840 to R.string.toolbox_compress_res_4k,
            2560 to R.string.toolbox_compress_res_2k,
            1920 to R.string.toolbox_compress_res_1080p,
            1280 to R.string.toolbox_compress_res_720p,
            854 to R.string.toolbox_compress_res_480p,
        )
        compressResolutionChipGroup?.let { group ->
            group.removeAllViews()
            resolutionPresets.forEachIndexed { index, (edge, labelRes) ->
                val chip = Chip(context).apply {
                    text = context.getString(labelRes)
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = index == 0
                }
                chip.setOnClickListener {
                    selectedResolutionMaxEdge = edge
                    group.check(chip.id)
                }
                group.addView(chip)
            }
        }

        // 2. Scale ratio slider & chips
        compressScaleSlider?.let { slider ->
            slider.value = 100f
            slider.addOnChangeListener { _, value, fromUser ->
                val ratio = value.toInt()
                compressScaleValue?.text = "${ratio}%"
                selectedScaleRatio = value / 100f
                if (fromUser) {
                    compressScaleChipGroup?.clearCheck()
                }
            }
        }

        val scalePresets = listOf(100 to "100%", 75 to "75%", 50 to "50%", 25 to "25%")
        compressScaleChipGroup?.let { group ->
            group.removeAllViews()
            scalePresets.forEachIndexed { index, (pct, label) ->
                val chip = Chip(context).apply {
                    text = label
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = index == 0
                }
                chip.setOnClickListener {
                    selectedScaleRatio = pct / 100f
                    compressScaleSlider?.value = pct.toFloat()
                    compressScaleValue?.text = "${pct}%"
                    group.check(chip.id)
                }
                group.addView(chip)
            }
        }

        // 3. Quality presets
        val qualityPresets = listOf(
            85 to R.string.toolbox_compress_quality_high,
            70 to R.string.toolbox_compress_quality_medium,
            50 to R.string.toolbox_compress_quality_low,
            35 to R.string.toolbox_compress_quality_economy,
        )
        compressQualityChipGroup?.let { group ->
            group.removeAllViews()
            qualityPresets.forEachIndexed { index, (q, labelRes) ->
                val chip = Chip(context).apply {
                    text = context.getString(labelRes)
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = index == 0
                }
                chip.setOnClickListener {
                    selectedQuality = q
                    selectedMaxBytes = null
                    compressSizeChipGroup?.clearCheck()
                    group.check(chip.id)
                }
                group.addView(chip)
            }
        }

        // 4. Target file size presets (no meaningless 'no limit' chip)
        val sizePresets = listOf(
            200 * 1024L to R.string.toolbox_compress_size_200k,
            500 * 1024L to R.string.toolbox_compress_size_500k,
            1 * 1024 * 1024L to R.string.toolbox_compress_size_1m,
            2 * 1024 * 1024L to R.string.toolbox_compress_size_2m,
            5 * 1024 * 1024L to R.string.toolbox_compress_size_5m,
            10 * 1024 * 1024L to R.string.toolbox_compress_size_10m,
        )
        compressSizeChipGroup?.let { group ->
            group.removeAllViews()
            sizePresets.forEachIndexed { index, (bytes, labelRes) ->
                val chip = Chip(context).apply {
                    text = context.getString(labelRes)
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = false
                }
                chip.setOnClickListener {
                    if (selectedMaxBytes == bytes) {
                        // Deselect size budget and revert to quality mode
                        selectedMaxBytes = null
                        group.clearCheck()
                        (compressQualityChipGroup?.getChildAt(0) as? Chip)?.let { qc ->
                            compressQualityChipGroup?.check(qc.id)
                            selectedQuality = 85
                        }
                    } else {
                        selectedMaxBytes = bytes
                        group.check(chip.id)
                        compressQualityChipGroup?.clearCheck()
                    }
                }
                group.addView(chip)
            }
        }
    }

    private fun setupConvertChips() {
        val targets = listOf(
            ImageTargetFormat.JPEG,
            ImageTargetFormat.PNG,
            ImageTargetFormat.WEBP,
            ImageTargetFormat.PDF,
        )
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

    private fun setupVideoToGifModernUi() {
        // Duration slider 1..15s
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
                    id = View.generateViewId()
                    isChecked = fps == selectedFps
                }
                chip.setOnClickListener {
                    selectedFps = fps
                    group.check(chip.id)
                    updateEstimate()
                }
                group.addView(chip)
            }
        }

        // Quality / resolution chips: map to maxEdge
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
                    id = View.generateViewId()
                    isChecked = edge == selectedMaxEdge
                }
                chip.setOnClickListener {
                    selectedMaxEdge = edge
                    group.check(chip.id)
                    updateEstimate()
                }
                group.addView(chip)
            }
        }

        // Sync legacy seekbar to slider for backward compatibility
        legacySeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val seconds = progress.coerceAtLeast(1)
                    durationSlider?.value = seconds.toFloat()
                    durationValue?.text = "${seconds}s"
                    updateEstimate()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateEstimate()
    }

    private fun loadVideoPreview() {
        val videoMedia = medias.firstOrNull { it.mediaType == MediaType.VIDEO } ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null
            try {
                val retriever = MediaMetadataRetriever()
                val fd = context.contentResolver.openAssetFileDescriptor(videoMedia.uri, "r")
                if (fd != null) {
                    retriever.setDataSource(fd.fileDescriptor)
                    fd.close()
                } else {
                    retriever.setDataSource(context, videoMedia.uri)
                }
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
        val options = CompressOptions(
            quality = selectedQuality,
            maxBytes = selectedMaxBytes,
            scaleRatio = selectedScaleRatio,
            maxEdge = selectedResolutionMaxEdge,
        )
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
        val isPdf = convertTarget == ImageTargetFormat.PDF
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

    private fun runVideoToGifModern() {
        val videoMedia = medias.firstOrNull { it.mediaType == MediaType.VIDEO } ?: return
        val seconds = (durationSlider?.value?.toInt() ?: legacySeekBar?.progress ?: 5).coerceAtLeast(1)
        val fps = selectedFps
        val maxEdge = selectedMaxEdge
        val mainHandler = Handler(Looper.getMainLooper())
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.videoToGif(
                context,
                videoMedia.uri,
                durationSeconds = seconds,
                fps = fps,
                maxEdge = maxEdge,
            ) { p ->
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
                val msg = if (convertTarget == ImageTargetFormat.PDF) {
                    context.getString(R.string.toolbox_done_pdf)
                } else if (count > 1) {
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
