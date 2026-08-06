/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.lineageos.glimpse.ext.applicationContext
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.utils.media.SourceAlbum
import org.lineageos.glimpse.utils.media.SourceAlbumAggregator

class AlbumsViewModel(application: Application) : GlimpseViewModel(application) {
    data class AlbumsRequest(
        val mediaType: MediaType? = null,
        val mimeType: String? = null,
    )

    private val _albumsRequest = MutableStateFlow<AlbumsRequest?>(null)
    val albumsRequest = _albumsRequest.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val albums = albumsRequest
        .filterNotNull()
        .flatMapLatest { albumsRequest ->
            mediaRepository.albums(
                albumsRequest.mediaType,
                albumsRequest.mimeType,
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = RequestStatus.Loading()
        )

    /**
     * Module 3: virtual aggregated "smart albums" sourced from known
     * crowd-sourcing / social apps. Aggregation is a purely-offline scan of the
     * MediaStore (path matching) with a bounded EXIF fallback; no files are
     * copied or moved.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sourceAlbums = albumsRequest
        .filterNotNull()
        .mapLatest {
            runCatching {
                SourceAlbumAggregator.aggregate(applicationContext)
            }.getOrDefault(emptyList())
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = emptyList<SourceAlbum>()
        )

    fun loadAlbums(albumsRequest: AlbumsRequest?) {
        _albumsRequest.value = albumsRequest
    }
}
