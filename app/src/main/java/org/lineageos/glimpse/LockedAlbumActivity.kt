/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.ext.createDeleteRequest
import org.lineageos.glimpse.ui.dialogs.PrivacyPinDialog
import org.lineageos.glimpse.utils.privacy.LockedVaultRepository
import org.lineageos.glimpse.utils.privacy.PrivacyLockManager
import org.lineageos.glimpse.utils.privacy.VaultItem

/**
 * Locked Album (私密相册).
 * Safely displays, imports, unlocks, or permanently deletes private photos and videos
 * isolated within the app's internal sandbox.
 */
class LockedAlbumActivity : AppCompatActivity(R.layout.activity_locked_album) {

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val recyclerView by lazy { findViewById<RecyclerView>(R.id.recyclerView) }
    private val emptyLayout by lazy { findViewById<View>(R.id.emptyLayout) }
    private val emptyAddButton by lazy { findViewById<Button>(R.id.emptyAddButton) }
    private val selectionActionBar by lazy { findViewById<View>(R.id.selectionActionBar) }
    private val selectionCountText by lazy { findViewById<TextView>(R.id.selectionCountText) }
    private val unlockToPublicButton by lazy { findViewById<Button>(R.id.unlockToPublicButton) }
    private val deletePermanentlyButton by lazy { findViewById<Button>(R.id.deletePermanentlyButton) }
    private val fabAdd by lazy { findViewById<FloatingActionButton>(R.id.fabAdd) }

    private var vaultItems = listOf<VaultItem>()
    private val selectedItems = mutableSetOf<String>()
    private var isLaunchingPicker = false

    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            loadVault()
        }

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        isLaunchingPicker = false
        if (uris.isNotEmpty()) {
            importUris(uris)
        }
    }

    private val adapter by lazy { VaultAdapter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        toolbar.setNavigationOnClickListener { finish() }

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener { launchPickMedia() }
        emptyAddButton.setOnClickListener { launchPickMedia() }

        unlockToPublicButton.setOnClickListener { unlockSelected() }
        deletePermanentlyButton.setOnClickListener { deleteSelected() }

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = navBars.bottom + 96)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(selectionActionBar) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = navBars.bottom + 8)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (!PrivacyLockManager.isSessionUnlocked()) {
            PrivacyPinDialog(this, onCancel = { finish() }) {
                loadVault()
            }.show(forcePrompt = true)
        } else {
            loadVault()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isLaunchingPicker) {
            PrivacyLockManager.lockSession()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PrivacyLockManager.lockSession()
    }

    private fun launchPickMedia() {
        isLaunchingPicker = true
        pickMediaLauncher.launch("image/*")
    }

    private fun importUris(uris: List<Uri>) {
        lifecycleScope.launch {
            var successCount = 0
            val pendingConsentUris = mutableListOf<Uri>()
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    if (LockedVaultRepository.importToVault(this@LockedAlbumActivity, uri) != null) {
                        successCount++
                        val deleted = runCatching {
                            contentResolver.delete(uri, null, null) > 0
                        }.getOrDefault(false)
                        if (!deleted) {
                            pendingConsentUris.add(uri)
                        }
                    }
                }
            }
            if (pendingConsentUris.isNotEmpty()) {
                runCatching {
                    deleteRequestLauncher.launch(
                        contentResolver.createDeleteRequest(*pendingConsentUris.toTypedArray())
                    )
                }.onFailure {
                    loadVault()
                }
            } else {
                loadVault()
            }
            if (successCount > 0) {
                Toast.makeText(
                    this@LockedAlbumActivity,
                    getString(R.string.privacy_locked_import_success, successCount),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun unlockSelected() {
        val toUnlock = vaultItems.filter { it.id in selectedItems }
        if (toUnlock.isEmpty()) return

        lifecycleScope.launch {
            var restoredCount = 0
            withContext(Dispatchers.IO) {
                toUnlock.forEach { item ->
                    if (LockedVaultRepository.restoreToPublic(this@LockedAlbumActivity, item) != null) {
                        restoredCount++
                    }
                }
            }
            clearSelection()
            loadVault()
            Toast.makeText(
                this@LockedAlbumActivity,
                getString(R.string.privacy_locked_unlocked_success, restoredCount),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun deleteSelected() {
        val toDelete = vaultItems.filter { it.id in selectedItems }
        if (toDelete.isEmpty()) return

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                toDelete.forEach { item ->
                    LockedVaultRepository.deleteItem(this@LockedAlbumActivity, item)
                }
            }
            clearSelection()
            loadVault()
            Toast.makeText(
                this@LockedAlbumActivity,
                getString(R.string.privacy_locked_deleted_success, toDelete.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadVault() {
        lifecycleScope.launch {
            vaultItems = withContext(Dispatchers.IO) {
                LockedVaultRepository.getVaultItems(this@LockedAlbumActivity)
            }
            adapter.notifyDataSetChanged()
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        val empty = vaultItems.isEmpty()
        emptyLayout.isVisible = empty
        recyclerView.isVisible = !empty
    }

    private fun toggleSelection(item: VaultItem) {
        if (selectedItems.contains(item.id)) {
            selectedItems.remove(item.id)
        } else {
            selectedItems.add(item.id)
        }
        updateSelectionState()
        adapter.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selectedItems.clear()
        updateSelectionState()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionState() {
        val hasSelection = selectedItems.isNotEmpty()
        selectionActionBar.isVisible = hasSelection
        fabAdd.isVisible = !hasSelection
        selectionCountText.text = getString(R.string.selection_count, selectedItems.size)
    }

    private fun previewVaultItem(item: VaultItem) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_license_viewer, null)
        val titleText = view.findViewById<TextView>(R.id.viewerTitle)
        val imageView = view.findViewById<ImageView>(R.id.viewerImage)
        val deleteButton = view.findViewById<Button>(R.id.viewerDeleteButton)
        val replaceButton = view.findViewById<Button>(R.id.viewerReplaceButton)
        val closeButton = view.findViewById<Button>(R.id.viewerCloseButton)

        titleText.text = item.displayName
        replaceButton.setText(R.string.privacy_locked_unlock_public)

        Glide.with(this)
            .load(item.file)
            .into(imageView)

        deleteButton.setOnClickListener {
            LockedVaultRepository.deleteItem(this, item)
            loadVault()
            dialog.dismiss()
        }

        replaceButton.setOnClickListener {
            LockedVaultRepository.restoreToPublic(this, item)
            loadVault()
            dialog.dismiss()
            Toast.makeText(this, R.string.privacy_locked_unlocked_toast, Toast.LENGTH_SHORT).show()
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    inner class VaultAdapter : RecyclerView.Adapter<VaultAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_media, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = vaultItems.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(vaultItems[position])
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val thumbnail = view.findViewById<ImageView>(R.id.thumbnailImage)
            private val videoBadge = view.findViewById<ImageView>(R.id.videoBadge)
            private val checkIndicator = view.findViewById<ImageView>(R.id.checkIndicator)

            fun bind(item: VaultItem) {
                videoBadge.isVisible = item.isVideo
                val isSelected = selectedItems.contains(item.id)
                checkIndicator.isVisible = selectedItems.isNotEmpty() && isSelected

                Glide.with(itemView.context)
                    .load(item.file)
                    .centerCrop()
                    .into(thumbnail)

                itemView.setOnClickListener {
                    if (selectedItems.isNotEmpty()) {
                        toggleSelection(item)
                    } else {
                        previewVaultItem(item)
                    }
                }

                itemView.setOnLongClickListener {
                    toggleSelection(item)
                    true
                }
            }
        }
    }
}
