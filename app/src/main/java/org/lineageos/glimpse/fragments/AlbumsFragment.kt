/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.fragments

import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ViewActivity
import org.lineageos.glimpse.ext.getViewProperty
import org.lineageos.glimpse.ext.updatePadding
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.ui.recyclerview.AlbumThumbnailLayoutManager
import org.lineageos.glimpse.ui.recyclerview.AlbumsAdapter
import org.lineageos.glimpse.utils.PermissionsChecker
import org.lineageos.glimpse.utils.PermissionsUtils
import org.lineageos.glimpse.utils.media.SourceAlbum
import org.lineageos.glimpse.viewmodels.AlbumsViewModel
import org.lineageos.glimpse.viewmodels.IntentsViewModel

/**
 * An albums list visualizer.
 */
class AlbumsFragment : Fragment(R.layout.fragment_albums) {
    // View models
    private val albumsViewModel by viewModels<AlbumsViewModel>()
    private val intentsViewModel by activityViewModels<IntentsViewModel>()

    // Views
    private val noMediaLinearLayout by getViewProperty<LinearLayout>(R.id.noMediaLinearLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)

    // RecyclerView
    private val adapter by lazy {
        AlbumsAdapter(
            onAlbumClick = { album ->
                when (intentsViewModel.isPicking.value) {
                    true -> findNavController().navigate(
                        R.id.action_albumsFragment_to_fragment_album,
                        AlbumFragment.createBundle(albumUri = album.uri)
                    )

                    false -> findNavController().navigate(
                        R.id.action_mainFragment_to_fragment_album,
                        AlbumFragment.createBundle(albumUri = album.uri)
                    )
                }
            },
            onSourceAlbumClick = { sourceAlbum ->
                openSourceAlbum(sourceAlbum)
            },
        )
    }

    // Latest observed snapshots (module 3 merges them into one adapter).
    private var currentAlbums: List<Album> = emptyList()
    private var currentSourceAlbums: List<SourceAlbum> = emptyList()

    /**
     * Module 3: open a virtual source album in the viewer. The matched MediaStore
     * URIs are passed via clip data so the existing ViewActivity multi-media
     * path renders them as a swipeable list.
     */
    private fun openSourceAlbum(sourceAlbum: SourceAlbum) {
        val context = requireContext()
        val uris = sourceAlbum.uris
        if (uris.isEmpty()) return
        val intent = Intent(context, ViewActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            type = "image/*"
            val clip = ClipData.newUri(context.contentResolver, sourceAlbum.displayName, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipData = clip
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    // Permissions
    private val permissionsChecker = PermissionsChecker(this, PermissionsUtils.mainPermissions)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            recyclerView.updatePadding(
                insets,
                start = true,
                end = true,
            )

            windowInsets
        }

        val context = requireContext()

        applyLayoutManager()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsChecker.withPermissionsGranted {
                    loadData()
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.layoutManager = null
        recyclerView.adapter = null

        super.onDestroyView()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        applyLayoutManager()
    }

    /**
     * (Re)create the grid layout manager and make the full-width source-carousel
     * row span every column.
     */
    private fun applyLayoutManager() {
        val lm = AlbumThumbnailLayoutManager(requireContext())
        recyclerView.layoutManager = lm
        recyclerView.adapter = adapter
        adapter.configureSpan(lm)
    }

    private suspend fun loadData() {
        coroutineScope {
            launch {
                intentsViewModel.parsedIntent.collectLatest {
                    when (it) {
                        is IntentsViewModel.ParsedIntent.PickIntent -> {
                            albumsViewModel.loadAlbums(
                                AlbumsViewModel.AlbumsRequest(
                                    mediaType = it.mediaType,
                                    mimeType = it.mimeType,
                                )
                            )
                        }

                        else -> albumsViewModel.loadAlbums(
                            AlbumsViewModel.AlbumsRequest()
                        )
                    }
                }
            }

            launch {
                albumsViewModel.albums.collectLatest {
                    when (it) {
                        is RequestStatus.Loading -> {
                            // Do nothing
                        }

                        is RequestStatus.Success -> {
                            currentAlbums = it.data
                            adapter.submit(currentAlbums, currentSourceAlbums)

                            val isEmpty = it.data.isEmpty() && currentSourceAlbums.isEmpty()
                            recyclerView.isVisible = !isEmpty
                            noMediaLinearLayout.isVisible = isEmpty
                        }

                        is RequestStatus.Error -> {
                            Log.e(LOG_TAG, "Failed to load albums, error: ${it.error}")

                            currentAlbums = emptyList()
                            adapter.submit(currentAlbums, currentSourceAlbums)

                            recyclerView.isVisible = false
                            noMediaLinearLayout.isVisible = currentSourceAlbums.isEmpty()
                        }
                    }
                }
            }

            // Module 3: virtual aggregated source albums
            launch {
                albumsViewModel.sourceAlbums.collectLatest { sourceAlbums ->
                    currentSourceAlbums = sourceAlbums
                    adapter.submit(currentAlbums, currentSourceAlbums)
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = AlbumsFragment::class.simpleName!!
    }
}
