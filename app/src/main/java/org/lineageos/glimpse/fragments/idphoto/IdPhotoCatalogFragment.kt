/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.fragments.idphoto

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.glimpse.IdPhotoActivity
import org.lineageos.glimpse.R
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.models.idphoto.PresetGroup
import org.lineageos.glimpse.viewmodels.IdPhotoViewModel

class IdPhotoCatalogFragment : Fragment(R.layout.fragment_id_photo_catalog) {

    private val viewModel: IdPhotoViewModel by activityViewModels()

    private lateinit var groupChipGroup: ChipGroup
    private lateinit var presetRecyclerView: RecyclerView

    private val adapter = IdPhotoPresetAdapter { preset ->
        viewModel.selectPreset(preset)
        (activity as? IdPhotoActivity)?.navigateToFraming()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupChipGroup = view.findViewById(R.id.groupChipGroup)
        presetRecyclerView = view.findViewById(R.id.presetRecyclerView)

        setupRecyclerView()
        setupGroupChips()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedGroup.collectLatest { group ->
                    val list = if (group == null) {
                        IdPhotoCatalog.presets
                    } else {
                        IdPhotoCatalog.getByGroup(group)
                    }
                    adapter.submitList(list)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        presetRecyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        presetRecyclerView.adapter = adapter
    }

    private fun setupGroupChips() {
        groupChipGroup.removeAllViews()
        val groups = listOf(
            null to R.string.id_photo_group_all,
            PresetGroup.COMMON to R.string.id_photo_group_common,
            PresetGroup.OFFICIAL to R.string.id_photo_group_official,
            PresetGroup.VISA to R.string.id_photo_group_visa,
            PresetGroup.EXAM to R.string.id_photo_group_exam,
        )

        val currentGroup = viewModel.selectedGroup.value

        groups.forEachIndexed { index, (group, labelRes) ->
            val chip = Chip(requireContext()).apply {
                text = getString(labelRes)
                isCheckable = true
                id = index
                isChecked = (group == currentGroup)
            }
            chip.setOnClickListener {
                viewModel.selectGroup(group)
            }
            groupChipGroup.addView(chip)
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
