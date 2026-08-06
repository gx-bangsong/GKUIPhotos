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
 * GIF). All heavy work runs on background dispatchers; a progress bar keeps the
 * UI responsive. No network access is performed.
 *
 * HCI redesign for video→GIF (2026-08): the previous single “Duration” slider
 * violated visibility/mapping/control heuristics — users could not see what
 * segment would be converted, could not pick a start offset, fps or resolution,
 * and had no estimate of output size. The new section exposes a preview,
 * range (start + duration), fps and resolution chips, and a live estimate so
 * the gulf of execution/evaluation is closed.
 */
class ImageToolboxBottomSheet(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val media: Media,
) : BottomSheetDialog(context) {

    private val isVideo = media.mediaType == MediaType.VIDEO
    private val isGif = media.mediaType == MediaType.IMAGE && (
        media.mimeType.contains("gif", ignoreCase = true) ||
            (media.displayName?.endsWith(".gif", ignoreCase = true) == true)
        )

    private val compressSection by lazy { findViewById<android.view.View>(R.id.compressSection)!! }
    private val convertSection by lazy { findViewById<android.view.View>(R.id.convertSection)!! }
    private val videoToGifSection by lazy { findViewById<android.view.View>(R.id.videoToGifSection)!! }
    private val gifEditorSection by lazy { findViewById<android.view.View>(R.id.gifEditorSection)!! }
    private val compressRadioGroup by lazy { findViewById<RadioGroup>(R.id.compressRadioGroup)!! }
    private val convertChipGroup by lazy { findViewById<ChipGroup>(R.id.convertChipGroup)!! }
    private val progressBar by lazy { findViewById<LinearProgressIndicator>(R.id.toolboxProgressBar)!! }

    // Video→GIF HCI controls
    private val gifStartSeekBar by lazy { findViewById<SeekBar>(R.id.gifStartSeekBar)!! }
    private val gifStartLabel by lazy { findViewById<TextView>(R.id.gifStartLabel)!! }
    private val gifDurationSeekBar by lazy { findViewById<SeekBar>(R.id.gifDurationSeekBar)!! }
    private val gifDurationLabel by lazy { findViewById<TextView>(R.id.gifDurationLabel)!! }
    private val gifVideoDurationLabel by lazy { findViewById<TextView>(R.id.gifVideoDurationLabel)!! }
    private val gifFpsChipGroup by lazy { findViewById<ChipGroup>(R.id.gifFpsChipGroup)!! }
    private val gifResChipGroup by lazy { findViewById<ChipGroup>(R.id.gifResChipGroup)!! }
    private val gifEstimateLabel by lazy { findViewById<TextView>(R.id.gifEstimateLabel)!! }
    private val gifPreviewImage by lazy { findViewById<ImageView>(R.id.gifPreviewImage)!! }

    private var convertTarget: ImageTargetFormat = ImageTargetFormat.JPEG

    // HCI state
    private var videoDurationSec = 15
    private var videoDurationMs = 15000L
    private var selectedFps = 8
    private var selectedEdge = 480

    init {
        setContentView(R.layout.dialog_image_toolbox)

        // Images expose compress + convert; videos expose video→GIF; GIFs expose the
        // dedicated GIF editor entry so the module is discoverable even before
        // the user hits the Edit button.
        when {
            isGif -> {
                compressSection.isVisible = false
                convertSection.isVisible = false
                videoToGifSection.isVisible = false
                gifEditorSection.isVisible = true
            }
            isVideo -> {
                compressSection.isVisible = false
                convertSection.isVisible = false
                videoToGifSection.isVisible = true
                gifEditorSection.isVisible = false
                setupVideoToGifSection()
            }
            else -> {
                compressSection.isVisible = true
                convertSection.isVisible = true
                videoToGifSection.isVisible = false
                gifEditorSection.isVisible = false
            }
        }

        setupConvertChips()

        findViewById<MaterialButton>(R.id.compressRunButton)!!.setOnClickListener {
            runCompress()
        }
        findViewById<MaterialButton>(R.id.convertRunButton)!!.setOnClickListener {
            runConvert()
        }
        findViewById<MaterialButton>(R.id.videoToGifRunButton)!!.setOnClickListener {
            runVideoToGif()
        }
        findViewById<MaterialButton>(R.id.gifPreviewButton)?.setOnClickListener {
            previewGifStart()
        }
        findViewById<MaterialButton>(R.id.gifEditorOpenButton)?.setOnClickListener {
            dismiss()
            context.startActivity(org.lineageos.glimpse.GifEditorActivity.createIntent(context, media.uri))
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

    private fun setupVideoToGifSection() {
        // Fps chips: 6/8/10/12 — affordance of trade-off between smoothness and size
        val fpsOptions = listOf(6, 8, 10, 12)
        fpsOptions.forEach { fps ->
            val chip = Chip(context).apply {
                text = "${fps}fps"
                isCheckable = true
                isChecked = fps == selectedFps
            }
            chip.setOnClickListener {
                selectedFps = fps
                updateEstimate()
            }
            gifFpsChipGroup.addView(chip)
        }
        gifFpsChipGroup.isSingleSelection = true
        gifFpsChipGroup.isSelectionRequired = true

        // Resolution chips: 360p/480p/720p — maps to maxEdge 360/480/720
        val resOptions = listOf(360 to "360p", 480 to "480p", 720 to "720p")
        resOptions.forEach { (edge, label) ->
            val chip = Chip(context).apply {
                text = label
                isCheckable = true
                isChecked = edge == selectedEdge
            }
            chip.setOnClickListener {
                selectedEdge = edge
                updateEstimate()
            }
            gifResChipGroup.addView(chip)
        }
        gifResChipGroup.isSingleSelection = true
        gifResChipGroup.isSelectionRequired = true

        // Duration SeekBar: 2..12s (max 10 steps -> 2+progress)
        gifDurationSeekBar.max = 10
        gifDurationSeekBar.progress = 3 // 5s default (2+3)
        updateDurationLabel()
        gifDurationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                updateDurationLabel()
                updateStartRange()
                updateEstimate()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Start SeekBar will be ranged after we know video duration
        gifStartSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                updateStartLabel()
                updateEstimate()
                if (fromUser) previewGifStart()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Fetch video duration off main thread for visibility of system status
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var durMs = 0L
            var retriever: MediaMetadataRetriever? = null
            var fd: android.content.res.AssetFileDescriptor? = null
            try {
                retriever = MediaMetadataRetriever()
                try {
                    fd = context.contentResolver.openAssetFileDescriptor(media.uri, "r")
                    if (fd != null) retriever.setDataSource(fd.fileDescriptor)
                    else retriever.setDataSource(context, media.uri)
                } catch (_: Exception) {
                    retriever.setDataSource(context, media.uri)
                }
                durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
            } finally {
                try { fd?.close() } catch (_: Exception) {}
                try { retriever?.release() } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                if (durMs > 0) {
                    videoDurationMs = durMs
                    videoDurationSec = (durMs / 1000).toInt().coerceAtLeast(1)
                    gifVideoDurationLabel.text = "视频总长：${String.format("%.1f", durMs / 1000f)}s"
                    updateStartRange()
                    updateEstimate()
                    previewGifStart()
                } else {
                    gifVideoDurationLabel.text = "视频总长：未知（将截取前 5s）"
                    gifEstimateLabel.isVisible = true
                    gifEstimateLabel.text = "预计：约 ${ (getDurationSec()*selectedFps).coerceAtMost(36)} 帧 · 分辨率 ${selectedEdge}p"
                }
            }
        }
        updateEstimate()
    }

    private fun getDurationSec(): Int = (gifDurationSeekBar.progress + 2).coerceIn(2, 12)
    private fun getStartSec(): Int {
        val maxStart = (videoDurationSec - getDurationSec()).coerceAtLeast(0)
        if (maxStart == 0) return 0
        return ((gifStartSeekBar.progress / 100f) * maxStart).toInt().coerceIn(0, maxStart)
    }

    private fun updateStartRange() {
        val maxStart = (videoDurationSec - getDurationSec()).coerceAtLeast(0)
        // Avoid feedback loop when updating label
        gifStartLabel.text = "起始位置：${String.format("%.1f", getStartSec().toFloat())}s / ${videoDurationSec}s"
        // If maxStart is 0, keep seek at 0 and disable (gulf of execution: no need to pick)
        gifStartSeekBar.isEnabled = maxStart > 0
    }

    private fun updateStartLabel() {
        gifStartLabel.text = "起始位置：${String.format("%.1f", getStartSec().toFloat())}s / ${videoDurationSec}s"
    }

    private fun updateDurationLabel() {
        gifDurationLabel.text = "截取时长：${getDurationSec()}s"
    }

    private fun updateEstimate() {
        val frames = (getDurationSec() * selectedFps).coerceAtMost(36)
        // Rough size: frames * (edge^2 * 0.4 / 1024) KB — enough for affordance, not precise
        val estKb = (frames * selectedEdge * selectedEdge * 0.4 / 1024).toInt().coerceAtLeast(50)
        val estStr = if (estKb > 1024) String.format("%.1f MB", estKb / 1024f) else "${estKb} KB"
        gifEstimateLabel.isVisible = true
        gifEstimateLabel.text = "预计：约 ${frames} 帧 · ${selectedEdge}p · ${selectedFps}fps · 约 $estStr"
    }

    private fun previewGifStart() {
        val startUs = getStartSec() * 1_000_000L
        gifPreviewImage.isVisible = true
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null
            var retriever: MediaMetadataRetriever? = null
            var fd: android.content.res.AssetFileDescriptor? = null
            try {
                retriever = MediaMetadataRetriever()
                try {
                    fd = context.contentResolver.openAssetFileDescriptor(media.uri, "r")
                    if (fd != null) retriever.setDataSource(fd.fileDescriptor)
                    else retriever.setDataSource(context, media.uri)
                } catch (_: Exception) { retriever.setDataSource(context, media.uri) }
                bmp = retriever.getFrameAtTime(startUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(startUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                bmp = bmp?.let {
                    val maxEdge = 320
                    val longest = maxOf(it.width, it.height)
                    if (longest > maxEdge) {
                        val scale = maxEdge.toFloat() / longest
                        Bitmap.createScaledBitmap((it.width*scale).toInt(), (it.height*scale).toInt(), true).also { _ -> it.recycle() }
                    } else it
                }
            } catch (_: Exception) {
            } finally {
                try { fd?.close() } catch (_: Exception) {}
                try { retriever?.release() } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                if (bmp != null) gifPreviewImage.setImageBitmap(bmp) else gifPreviewImage.isVisible = false
            }
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
        launchHeavy {
            ImageToolbox.compress(context, media.uri, options)
        }
    }

    private fun runConvert() {
        launchHeavy {
            ImageToolbox.convert(context, media.uri, convertTarget)
        }
    }

    private fun runVideoToGif() {
        val startSec = getStartSec()
        val durSec = getDurationSec()
        val fps = selectedFps
        val edge = selectedEdge
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.videoToGif(
                context, media.uri,
                startSeconds = startSec,
                durationSeconds = durSec,
                fps = fps,
                maxEdge = edge,
            ) { p -> mainHandler.post { onProgress(p) } }
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
                // Surface the real cause in logcat so failures are diagnosable
                // (the user-facing toast stays generic).
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
