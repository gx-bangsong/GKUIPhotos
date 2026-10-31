/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.dialogs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.models.idphoto.PresetGroup
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.utils.media.IdPhotoGenerator

class IdPhotoBottomSheet(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val media: Media,
    private val onComplete: (() -> Unit)? = null,
) : BottomSheetDialog(context) {

    private val titleTextView by lazy { findViewById<TextView>(R.id.idPhotoTitleTextView)!! }
    private val backButton by lazy { findViewById<ImageButton>(R.id.idPhotoBackButton)!! }
    private val closeButton by lazy { findViewById<ImageButton>(R.id.idPhotoCloseButton)!! }
    private val progressBar by lazy { findViewById<LinearProgressIndicator>(R.id.idPhotoProgressBar)!! }

    private val catalogSection by lazy { findViewById<View>(R.id.catalogSection)!! }
    private val groupChipGroup by lazy { findViewById<ChipGroup>(R.id.groupChipGroup)!! }
    private val presetRecyclerView by lazy { findViewById<RecyclerView>(R.id.presetRecyclerView)!! }

    private val framingSection by lazy { findViewById<View>(R.id.framingSection)!! }
    private val framingSpecTextView by lazy { findViewById<TextView>(R.id.framingSpecTextView)!! }
    private val framingCropImageView by lazy { findViewById<CropImageView>(R.id.framingCropImageView)!! }
    private val sameRatioContainer by lazy { findViewById<View>(R.id.sameRatioContainer)!! }
    private val sameRatioChipGroup by lazy { findViewById<ChipGroup>(R.id.sameRatioChipGroup)!! }
    private val generateButton by lazy { findViewById<MaterialButton>(R.id.generateButton)!! }

    private var previewBitmap: Bitmap? = null
    private var selectedPreset: IdPhotoPreset? = null
    private val adapter = IdPhotoPresetAdapter { preset ->
        enterFraming(preset)
    }

    init {
        setContentView(R.layout.dialog_id_photo)

        closeButton.setOnClickListener { dismiss() }
        backButton.setOnClickListener { showCatalog() }

        setupRecyclerView()
        setupGroupChips()
        loadPreviewBitmap()

        generateButton.setOnClickListener {
            onGenerateClicked()
        }
    }

    private fun setupRecyclerView() {
        presetRecyclerView.layoutManager = GridLayoutManager(context, 2)
        presetRecyclerView.adapter = adapter
        adapter.submitList(IdPhotoCatalog.presets)
    }

    private fun setupGroupChips() {
        val groups = listOf(
            null to R.string.id_photo_group_all,
            PresetGroup.COMMON to R.string.id_photo_group_common,
            PresetGroup.OFFICIAL to R.string.id_photo_group_official,
            PresetGroup.VISA to R.string.id_photo_group_visa,
            PresetGroup.EXAM to R.string.id_photo_group_exam,
        )

        groups.forEachIndexed { index, (group, labelRes) ->
            val chip = Chip(context).apply {
                text = context.getString(labelRes)
                isCheckable = true
                id = index
                isChecked = index == 0
            }
            chip.setOnClickListener {
                val list = if (group == null) {
                    IdPhotoCatalog.presets
                } else {
                    IdPhotoCatalog.getByGroup(group)
                }
                adapter.submitList(list)
            }
            groupChipGroup.addView(chip)
        }
    }

    private fun loadPreviewBitmap() {
        lifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                loadDownsampledBitmap(media.uri)
            }
            if (bmp != null) {
                previewBitmap = bmp
                framingCropImageView.setBitmap(bmp)
            }
        }
    }

    private fun loadDownsampledBitmap(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxEdge = 2048
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun enterFraming(preset: IdPhotoPreset) {
        val previousPreset = selectedPreset
        selectedPreset = preset

        catalogSection.isVisible = false
        framingSection.isVisible = true
        backButton.isVisible = true

        titleTextView.text = context.getString(preset.nameRes)
        framingSpecTextView.text = "${preset.widthMm}x${preset.heightMm}mm · ${preset.targetWidthPx}x${preset.targetHeightPx} px · ${preset.dpi} DPI"

        // If switching ratio or first time entering, update cropImageView aspect ratio.
        // CropImageView does not reset scale/translate on aspectRatio change.
        framingCropImageView.aspectRatio = preset.aspectRatio
        framingCropImageView.showFaceGuide = true

        if (previousPreset == null) {
            framingCropImageView.reset()
        }

        setupSameRatioChips(preset)
    }

    private fun setupSameRatioChips(preset: IdPhotoPreset) {
        sameRatioChipGroup.removeAllViews()
        val sameRatioList = IdPhotoCatalog.getSameRatioPresets(preset)
        if (sameRatioList.size <= 1) {
            sameRatioContainer.isVisible = false
            return
        }
        sameRatioContainer.isVisible = true

        sameRatioList.forEachIndexed { index, p ->
            val chip = Chip(context).apply {
                text = context.getString(p.nameRes)
                isCheckable = true
                id = index
                isChecked = p.id == preset.id
            }
            chip.setOnClickListener {
                if (selectedPreset?.id != p.id) {
                    selectedPreset = p
                    titleTextView.text = context.getString(p.nameRes)
                    framingSpecTextView.text = "${p.widthMm}x${p.heightMm}mm · ${p.targetWidthPx}x${p.targetHeightPx} px · ${p.dpi} DPI"
                    // Ratio is identical, framing is preserved without resetting
                    framingCropImageView.aspectRatio = p.aspectRatio
                }
            }
            sameRatioChipGroup.addView(chip)
        }
    }

    private fun showCatalog() {
        framingSection.isVisible = false
        catalogSection.isVisible = true
        backButton.isVisible = false
        titleTextView.text = context.getString(R.string.image_toolbox_id_photo)
    }

    private fun onGenerateClicked() {
        val preset = selectedPreset ?: return
        val normalizedRect = framingCropImageView.getNormalizedCropRect()
        if (normalizedRect == null) {
            Toast.makeText(context, R.string.id_photo_failed, Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.isVisible = true
        generateButton.isEnabled = false

        lifecycleOwner.lifecycleScope.launch {
            val resultUri = withContext(Dispatchers.IO) {
                runCatching {
                    IdPhotoGenerator.generate(
                        context = context,
                        sourceUri = media.uri,
                        cropRectNormalized = normalizedRect,
                        preset = preset,
                    )
                }.getOrNull()
            }

            progressBar.isVisible = false
            generateButton.isEnabled = true

            if (resultUri != null) {
                Toast.makeText(context, R.string.id_photo_success, Toast.LENGTH_SHORT).show()
                onComplete?.invoke()
                dismiss()
            } else {
                Toast.makeText(context, R.string.id_photo_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class IdPhotoPresetAdapter(
        private val onPresetSelected: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.Adapter<IdPhotoPresetViewHolder>() {

        private var items: List<IdPhotoPreset> = emptyList()

        fun submitList(newItems: List<IdPhotoPreset>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IdPhotoPresetViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_id_photo_preset, parent, false)
            return IdPhotoPresetViewHolder(view, onPresetSelected)
        }

        override fun onBindViewHolder(holder: IdPhotoPresetViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class IdPhotoPresetViewHolder(
        itemView: View,
        private val onPresetSelected: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameText = itemView.findViewById<TextView>(R.id.presetNameText)
        private val specText = itemView.findViewById<TextView>(R.id.presetSpecText)
        private val usageText = itemView.findViewById<TextView>(R.id.presetUsageText)

        fun bind(preset: IdPhotoPreset) {
            nameText.setText(preset.nameRes)
            specText.text = "${preset.widthMm}x${preset.heightMm}mm · ${preset.targetWidthPx}x${preset.targetHeightPx} px · ${preset.dpi} DPI"
            usageText.text = preset.description
            itemView.setOnClickListener { onPresetSelected(preset) }
        }
    }
}
