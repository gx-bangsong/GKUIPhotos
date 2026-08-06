/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.dialogs

import android.content.Context
import android.widget.RadioGroup
import android.widget.SeekBar
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
    private val gifDurationSeekBar by lazy { findViewById<SeekBar>(R.id.gifDurationSeekBar)!! }
    private val progressBar by lazy { findViewById<LinearProgressIndicator>(R.id.toolboxProgressBar)!! }

    private var convertTarget: ImageTargetFormat = ImageTargetFormat.JPEG

    init {
        setContentView(R.layout.dialog_image_toolbox)

        // Only images expose compress + convert; only videos expose video→GIF.
        compressSection.isVisible = !isVideo
        convertSection.isVisible = !isVideo
        videoToGifSection.isVisible = isVideo

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
        val seconds = gifDurationSeekBar.progress.coerceAtLeast(1)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        launchHeavy(progressive = true) { onProgress ->
            ImageToolbox.videoToGif(context, media.uri, durationSeconds = seconds) { p ->
                // ImageToolbox reports progress from a background thread; hop back
                // to the main thread before touching the progress bar.
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
