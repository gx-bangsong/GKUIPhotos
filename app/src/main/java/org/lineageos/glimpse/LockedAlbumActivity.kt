/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.Intent
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
    private var isLaunchingSystemFlow = false
    private var pendingConsentStaged: List<Pair<VaultItem, Uri>>? = null

    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            isLaunchingSystemFlow = false
            val staged = pendingConsentStaged
            pendingConsentStaged = null

            if (result.resultCode == Activity.RESULT_OK) {
                val count = staged?.size ?: 0
                if (count > 0) {
                    Toast.makeText(
                        this,
                        getString(R.string.privacy_locked_import_success, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadVault()
            } else {
                // User denied or dismissed consent dialog: ROLLBACK
                staged?.let { items ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        LockedVaultRepository.deleteItems(this@LockedAlbumActivity, items.map { it.first })
                        withContext(Dispatchers.Main) {
                            loadVault()
                        }
                    }
                }
                Toast.makeText(
                    this,
                    R.string.privacy_locked_move_canceled,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            isLaunchingSystemFlow = false
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                } ?: data.data?.let { uris.add(it) }

                if (uris.isNotEmpty()) {
                    importUris(uris)
                }
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
        if (!isLaunchingSystemFlow) {
            PrivacyLockManager.lockSession()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PrivacyLockManager.lockSession()
    }

    private fun launchPickMedia() {
        isLaunchingSystemFlow = true
        val intent = Intent(this, PickerActivity::class.java).apply {
            action = Intent.ACTION_GET_CONTENT
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "*/*"
        }
        pickMediaLauncher.launch(intent)
    }

    private fun importUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val resolvedUris = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    LockedVaultRepository.resolveToMediaStoreUri(this@LockedAlbumActivity, uri)?.let {
                        uri to it
                    }
                }
            }

            if (resolvedUris.isEmpty()) {
                Toast.makeText(
                    this@LockedAlbumActivity,
                    R.string.privacy_locked_move_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val staged = withContext(Dispatchers.IO) {
                resolvedUris.mapNotNull { (originalUri, mediaStoreUri) ->
                    LockedVaultRepository.importToVault(this@LockedAlbumActivity, originalUri)?.let { vaultItem ->
                        vaultItem to mediaStoreUri
                    }
                }
            }

            if (staged.isEmpty()) {
                Toast.makeText(
                    this@LockedAlbumActivity,
                    R.string.privacy_locked_move_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val mediaStoreUris = staged.map { it.second }
            val canDirectDelete = withContext(Dispatchers.IO) {
                LockedVaultRepository.canDirectDelete(this@LockedAlbumActivity, mediaStoreUris)
            }

            if (canDirectDelete) {
                val deletedCount = withContext(Dispatchers.IO) {
                    var count = 0
                    staged.forEach { (item, mediaStoreUri) ->
                        val deleted = runCatching {
                            contentResolver.delete(mediaStoreUri, null, null) > 0
                        }.getOrDefault(false)
                        if (deleted) {
                            count++
                        } else {
                            LockedVaultRepository.deleteItem(this@LockedAlbumActivity, item)
                        }
                    }
                    count
                }
                if (deletedCount > 0) {
                    Toast.makeText(
                        this@LockedAlbumActivity,
                        getString(R.string.privacy_locked_import_success, deletedCount),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@LockedAlbumActivity,
                        R.string.privacy_locked_move_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadVault()
            } else {
                runCatching {
                    val request = contentResolver.createDeleteRequest(*mediaStoreUris.toTypedArray())
                    pendingConsentStaged = staged
                    isLaunchingSystemFlow = true
                    deleteRequestLauncher.launch(request)
                }.onFailure {
                    withContext(Dispatchers.IO) {
                        LockedVaultRepository.deleteItems(
                            this@LockedAlbumActivity,
                            staged.map { it.first }
                        )
                    }
                    Toast.makeText(
                        this@LockedAlbumActivity,
                        R.string.privacy_locked_move_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    loadVault()
                }
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
                LockedVaultRepository.deleteItems(this@LockedAlbumActivity, toDelete)
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
