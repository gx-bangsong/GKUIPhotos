/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.dialogs

import android.content.Context
import android.content.Intent
import android.widget.CheckBox
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ext.buildShareIntent
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.utils.media.ExifStripOptions
import org.lineageos.glimpse.utils.media.ExifStripper

/**
 * Module 2: bottom-sheet that lets the user strip EXIF metadata (location
 * and/or device+time) from an image before sharing. The original file is never
 * modified; a cleaned copy is written to the app cache and handed to the share
 * [Intent]. All work happens off the main thread.
 */
class SharePrivacyBottomSheet(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val media: Media,
) : BottomSheetDialog(context) {

    private val stripLocationCheckBox by lazy {
        findViewById<CheckBox>(R.id.stripLocationCheckBox)!!
    }
    private val stripDeviceCheckBox by lazy {
        findViewById<CheckBox>(R.id.stripDeviceCheckBox)!!
    }
    private val shareNowButton by lazy {
        findViewById<MaterialButton>(R.id.shareNowButton)!!
    }
    private val progressIndicator by lazy {
        findViewById<CircularProgressIndicator>(R.id.progressIndicator)!!
    }

    init {
        setContentView(R.layout.dialog_share_privacy)

        shareNowButton.setOnClickListener {
            val options = ExifStripOptions(
                stripLocation = stripLocationCheckBox.isChecked,
                stripDeviceAndTime = stripDeviceCheckBox.isChecked,
            )

            // If the user keeps everything, just share the original directly.
            if (!options.stripLocation && !options.stripDeviceAndTime) {
                shareOriginal()
                return@setOnClickListener
            }

            setLoading(true)
            lifecycleOwner.lifecycleScope.launch {
                val cleanedUri = withContext(Dispatchers.IO) {
                    runCatching {
                        ExifStripper.strip(context, media.uri, options)
                    }.getOrElse {
                        withContext(Dispatchers.Main) {
                            setLoading(false)
                            Toast.makeText(
                                context,
                                R.string.share_privacy_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        null
                    }
                } ?: return@launch

                setLoading(false)
                dismiss()

                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, cleanedUri)
                    type = media.mimeType
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }

    private fun shareOriginal() {
        dismiss()
        context.startActivity(Intent.createChooser(buildShareIntent(media), null))
    }

    private fun setLoading(loading: Boolean) {
        shareNowButton.isEnabled = !loading
        shareNowButton.isVisible = !loading
        progressIndicator.isVisible = loading
    }
}
