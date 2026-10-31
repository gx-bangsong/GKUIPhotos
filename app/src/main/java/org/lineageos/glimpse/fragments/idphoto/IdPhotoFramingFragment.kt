/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.fragments.idphoto

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.glimpse.IdPhotoActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.viewmodels.IdPhotoViewModel

class IdPhotoFramingFragment : Fragment(R.layout.fragment_id_photo_framing) {

    private val viewModel: IdPhotoViewModel by activityViewModels()

    private lateinit var cropImageView: CropImageView
    private lateinit var loadingIndicator: CircularProgressIndicator
    private lateinit var sameRatioContainer: View
    private lateinit var sameRatioChipGroup: ChipGroup
    private lateinit var generateButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cropImageView = view.findViewById(R.id.framingCropImageView)
        loadingIndicator = view.findViewById(R.id.framingLoadingIndicator)
        sameRatioContainer = view.findViewById(R.id.sameRatioContainer)
        sameRatioChipGroup = view.findViewById(R.id.sameRatioChipGroup)
        generateButton = view.findViewById(R.id.generateButton)

        val bottomControls = view.findViewById<View>(R.id.framingBottomControls)
        ViewCompat.setOnApplyWindowInsetsListener(bottomControls) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = navBars.bottom + 16)
            insets
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isBitmapLoading.collectLatest { isLoading ->
                        loadingIndicator.isVisible = isLoading
                        val bmp = viewModel.previewBitmap
                        if (bmp != null) {
                            if (viewModel.hasFramingTransform) {
                                cropImageView.setBitmap(
                                    bmp,
                                    Triple(
                                        viewModel.framingScale,
                                        viewModel.framingTranslateX,
                                        viewModel.framingTranslateY,
                                    ),
                                )
                            } else {
                                cropImageView.setBitmap(bmp)
                                val (scale, tx, ty) = cropImageView.getTransformState()
                                viewModel.updateTransform(scale, tx, ty)
                            }
                        }
                    }
                }

                launch {
                    viewModel.selectedPreset.collectLatest { preset ->
                        if (preset != null) {
                            cropImageView.aspectRatio = preset.aspectRatio
                            cropImageView.showFaceGuide = true
                            setupSameRatioChips(preset)
                            (activity as? IdPhotoActivity)?.updateToolbar()
                        }
                    }
                }
            }
        }

        generateButton.setOnClickListener {
            val preset = viewModel.selectedPreset.value ?: return@setOnClickListener
            val normalizedRect = cropImageView.getNormalizedCropRect()
            if (normalizedRect == null) {
                Toast.makeText(requireContext(), R.string.id_photo_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Save transform state before generating
            val (scale, tx, ty) = cropImageView.getTransformState()
            viewModel.updateTransform(scale, tx, ty)

            (activity as? IdPhotoActivity)?.executeSavePipeline(normalizedRect, preset)
        }
    }

    override fun onPause() {
        super.onPause()
        val (scale, tx, ty) = cropImageView.getTransformState()
        viewModel.updateTransform(scale, tx, ty)
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
            val chip = Chip(requireContext()).apply {
                text = getString(p.nameRes)
                isCheckable = true
                id = index
                isChecked = (p.id == preset.id)
            }
            chip.setOnClickListener {
                if (viewModel.selectedPreset.value?.id != p.id) {
                    // Update transform in view model first so it is preserved
                    val (scale, tx, ty) = cropImageView.getTransformState()
                    viewModel.updateTransform(scale, tx, ty)

                    // Switch preset: ratio is identical, framing is preserved
                    viewModel.selectPreset(p)
                }
            }
            sameRatioChipGroup.addView(chip)
        }
    }
}
