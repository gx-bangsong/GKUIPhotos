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
import kotlinx.coroutines.flow.combine
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
import org.lineageos.glimpse.utils.media.SourceAlbumRulesRepository

class AlbumsViewModel(application: Application) : GlimpseViewModel(application) {
    data class AlbumsRequest(
        val mediaType: MediaType? = null,
        val mimeType: String? = null,
    )

    private val _albumsRequest = MutableStateFlow<AlbumsRequest?>(null)
    val albumsRequest = _albumsRequest.asStateFlow()

    private val rulesRepository = SourceAlbumRulesRepository.getInstance(applicationContext)

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
     * Module 3: OPPO 式智能相册，自动识别外卖/打车/社交等应用拍摄的照片并生成专属图集。
     * 现已支持通过设置添加自定义规则，并可导入导出。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sourceAlbums = combine(
        albumsRequest.filterNotNull(),
        rulesRepository.rulesFlow
    ) { _, rules ->
        rules
    }.mapLatest { rules ->
        runCatching {
            if (!rulesRepository.isSmartAlbumEnabled()) {
                emptyList()
            } else {
                SourceAlbumAggregator.aggregateWithRules(
                    applicationContext,
                    rules,
                    maxExifScans = rulesRepository.getExifScanLimit()
                )
            }
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
