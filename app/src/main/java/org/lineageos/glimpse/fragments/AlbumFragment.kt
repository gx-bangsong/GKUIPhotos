/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.fragments

import android.app.Activity
import android.app.WallpaperManager
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.shape.MaterialShapeDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ViewActivity
import org.lineageos.glimpse.collage.CollageActivity
import org.lineageos.glimpse.collage.CollageSelectionError
import org.lineageos.glimpse.collage.collageSelectionError
import org.lineageos.glimpse.datasources.MediaError
import org.lineageos.glimpse.ext.buildShareIntent
import org.lineageos.glimpse.ext.createDeleteRequest
import org.lineageos.glimpse.ext.createTrashRequest
import org.lineageos.glimpse.ext.getParcelable
import org.lineageos.glimpse.ext.getSerializable
import org.lineageos.glimpse.ext.getViewProperty
import org.lineageos.glimpse.ext.kill
import org.lineageos.glimpse.ext.quickSelectDateEnabled
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.AlbumType
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.ui.dialogs.ImageToolboxBottomSheet
import org.lineageos.glimpse.ui.dialogs.PrivacyPinDialog
import org.lineageos.glimpse.ui.recyclerview.ThumbnailAdapter
import org.lineageos.glimpse.ui.recyclerview.SelectionGestureRecyclerView
import org.lineageos.glimpse.ui.recyclerview.ThumbnailItemDetailsLookup
import org.lineageos.glimpse.ui.recyclerview.ThumbnailLayoutManager
import org.lineageos.glimpse.utils.MediaDialogsUtils
import org.lineageos.glimpse.utils.PermissionsChecker
import org.lineageos.glimpse.utils.PermissionsUtils
import org.lineageos.glimpse.utils.privacy.LockedVaultRepository
import org.lineageos.glimpse.utils.privacy.VaultItem
import org.lineageos.glimpse.viewmodels.AlbumViewModel
import org.lineageos.glimpse.viewmodels.IntentsViewModel
import kotlin.reflect.safeCast

/**
 * A fragment showing a list of media from a specific album with thumbnails.
 */
class AlbumFragment : Fragment(R.layout.fragment_album) {
    // View models
    private val viewModel by viewModels<AlbumViewModel>()
    private val intentsViewModel by activityViewModels<IntentsViewModel>()

    // Views
    private val appBarLayout by getViewProperty<AppBarLayout>(R.id.appBarLayout)
    private val noMediaLinearLayout by getViewProperty<LinearLayout>(R.id.noMediaLinearLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val warningTrashTimePeriod by getViewProperty<TextView>(R.id.warningTrashTimePeriod)
    private val warningTrashTimePeriodToolbar by getViewProperty<TextView>(R.id.textView)

    // System services
    private val wallpaperManager by lazy {
        requireContext().getSystemService(WallpaperManager::class.java)
    }

    // RecyclerView
    private val thumbnailAdapter by lazy { ThumbnailAdapter() }

    // Selection
    private var selectionTracker: SelectionTracker<Media>? = null

    private val selectionTrackerObserver =
        object : SelectionTracker.SelectionObserver<Media>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()

                updateSelection()
            }

            override fun onSelectionRefresh() {
                super.onSelectionRefresh()

                updateSelection()
            }

            override fun onSelectionRestored() {
                super.onSelectionRestored()

                updateSelection()
            }
        }

    private var selectionActive = false
    private var pendingSelectionStart = false
    private var restoringGestureSelection = false
    private var selectionNavigationBar: View? = null
    private var navigationBarWasVisible = false
    private var selectionParentAppBar: View? = null
    private var parentAppBarWasVisible = false
    private var bottomInset = 0
    private var selectionPreDrawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var selectionBackCallback: OnBackPressedCallback? = null
    private val selectionTopBar by getViewProperty<View>(R.id.selectionTopBar)
    private val selectionCount by getViewProperty<TextView>(R.id.selectionCount)
    private val selectionSheet by getViewProperty<View>(R.id.selectionSheet)
    private val selectionSheetHint by getViewProperty<TextView>(R.id.selectionSheetDragHint)
    private val selectionSheetReason by getViewProperty<TextView>(R.id.selectionSheetReason)
    private val selectionPrimary by getViewProperty<LinearLayout>(R.id.selectionSheetPrimary)
    private val selectionMore by getViewProperty<LinearLayout>(R.id.selectionSheetMore)
    private val sheetBehavior get() = BottomSheetBehavior.from(selectionSheet)

    private fun onSelectionAction(item: MenuItem) =
            selectionTracker?.selection?.toList()?.toTypedArray()?.takeUnless {
                it.isEmpty()
            }?.let { selection ->
                when (item.itemId) {
                    R.id.deleteForever -> {
                        MediaDialogsUtils.openDeleteForeverDialog(requireContext(), *selection) {
                            deleteForeverContract.launch(
                                requireContext().contentResolver.createDeleteRequest(
                                    *it.map { media ->
                                        media.uri
                                    }.toTypedArray()
                                )
                            )
                        }

                        true
                    }

                    R.id.restoreFromTrash -> {
                        MediaDialogsUtils.openRestoreFromTrashDialog(requireContext(), *selection) {
                            trashMedias(false, *selection)
                        }

                        true
                    }

                    R.id.share -> {
                        requireActivity().startActivity(buildShareIntent(*selection))

                        true
                    }

                    R.id.collage -> {
                        when (collageSelectionError(selection.map { it.mediaType })) {
                            CollageSelectionError.VIDEO ->
                                Toast.makeText(requireContext(), R.string.collage_video_error, Toast.LENGTH_LONG).show()
                            CollageSelectionError.COUNT ->
                                Toast.makeText(requireContext(), R.string.collage_count_error, Toast.LENGTH_LONG).show()
                            null -> startActivity(CollageActivity.createIntent(requireContext(), selection.map { it.uri }))
                        }
                        true
                    }

                    R.id.toolbox -> {
                        val imageMedias = selection.filter { it.mediaType == MediaType.IMAGE }
                        if (imageMedias.isEmpty()) {
                            Toast.makeText(
                                requireContext(),
                                R.string.toolbox_no_images_selected,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            ImageToolboxBottomSheet(
                                requireContext(),
                                viewLifecycleOwner,
                                imageMedias,
                            ) {
                                selectionTracker?.clearSelection()
                            }.show()
                        }

                        true
                    }

                    R.id.lockMedia -> {
                        PrivacyPinDialog(requireContext()) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val context = requireContext()
                                val staged = withContext(Dispatchers.IO) {
                                    selection.mapNotNull { media ->
                                        LockedVaultRepository.importToVault(context, media.uri)?.let {
                                            it to media.uri
                                        }
                                    }
                                }

                                if (staged.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        R.string.privacy_locked_move_failed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    selectionTracker?.clearSelection()
                                    return@launch
                                }

                                val canDirectDelete = withContext(Dispatchers.IO) {
                                    LockedVaultRepository.canDirectDelete(context, staged.map { it.second })
                                }

                                if (canDirectDelete) {
                                    val deletedCount = withContext(Dispatchers.IO) {
                                        var count = 0
                                        staged.forEach { (item, uri) ->
                                            val deleted = runCatching {
                                                context.contentResolver.delete(uri, null, null) > 0
                                            }.getOrDefault(false)
                                            if (deleted) {
                                                count++
                                            } else {
                                                LockedVaultRepository.deleteItem(context, item)
                                            }
                                        }
                                        count
                                    }

                                    if (deletedCount > 0) {
                                        Toast.makeText(
                                            context,
                                            getString(R.string.privacy_locked_import_success, deletedCount),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            R.string.privacy_locked_move_failed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    selectionTracker?.clearSelection()
                                } else {
                                    val uris = staged.map { it.second }.toTypedArray()
                                    runCatching {
                                        val intentSenderRequest =
                                            context.contentResolver.createDeleteRequest(*uris)
                                        pendingLockMoveStaged = staged
                                        lastProcessedSelection = selection
                                        lockDeleteForeverContract.launch(intentSenderRequest)
                                    }.onFailure {
                                        withContext(Dispatchers.IO) {
                                            LockedVaultRepository.deleteItems(context, staged.map { it.first })
                                        }
                                        Toast.makeText(
                                            context,
                                            R.string.privacy_locked_move_failed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        selectionTracker?.clearSelection()
                                    }
                                }
                            }
                        }.show(forcePrompt = true)
                        true
                    }

                    R.id.moveToTrash -> {
                        MediaDialogsUtils.openMoveToTrashDialog(requireContext(), *selection) {
                            trashMedias(true, *selection)
                        }

                        true
                    }

                    R.id.done -> {
                        sendResult(*selection)
                        true
                    }

                    else -> false
                }
            } ?: false


    // Contracts
    private var lastProcessedSelection: Array<out Media>? = null

    private val deleteForeverContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != Activity.RESULT_CANCELED
            val count = lastProcessedSelection?.count() ?: 1

            MediaDialogsUtils.showDeleteForeverResultSnackbar(
                requireContext(),
                requireView(),
                succeeded, count,
            )

            lastProcessedSelection = null
            selectionTracker?.clearSelection()
        }

    private var pendingLockMoveStaged: List<Pair<VaultItem, Uri>>? = null

    private val lockDeleteForeverContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val staged = pendingLockMoveStaged
            pendingLockMoveStaged = null
            lastProcessedSelection = null

            if (result.resultCode == Activity.RESULT_OK) {
                val count = staged?.size ?: 0
                if (count > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.privacy_locked_import_success, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // User denied or dismissed consent dialog: ROLLBACK
                staged?.let { items ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        LockedVaultRepository.deleteItems(requireContext(), items.map { it.first })
                    }
                }
                Toast.makeText(
                    requireContext(),
                    R.string.privacy_locked_move_canceled,
                    Toast.LENGTH_SHORT
                ).show()
            }
            selectionTracker?.clearSelection()
        }

    private val trashContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != Activity.RESULT_CANCELED
            val count = lastProcessedSelection?.count() ?: 1

            MediaDialogsUtils.showMoveToTrashResultSnackbar(
                requireContext(),
                requireView(),
                succeeded, count,
                actionCallback = lastProcessedSelection?.let { trashedMedias ->
                    {
                        trashMedias(false, *trashedMedias)
                    }
                }
            )

            lastProcessedSelection = null
            selectionTracker?.clearSelection()
        }

    private val restoreFromTrashContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != Activity.RESULT_CANCELED
            val count = lastProcessedSelection?.count() ?: 1

            MediaDialogsUtils.showRestoreFromTrashResultSnackbar(
                requireContext(),
                requireView(),
                succeeded, count,
                actionCallback = lastProcessedSelection?.let { trashedMedias ->
                    {
                        trashMedias(true, *trashedMedias)
                    }
                }
            )

            lastProcessedSelection = null
            selectionTracker?.clearSelection()
        }

    // Arguments
    private val albumType: AlbumType?
        get() = requireArguments().getSerializable(ARG_ALBUM_TYPE, AlbumType::class)
    private val albumUri: Uri?
        get() = requireArguments().getParcelable(ARG_ALBUM_URI, Uri::class)
    private val mediaType: MediaType?
        get() = requireArguments().getSerializable(ARG_MEDIA_TYPE, MediaType::class)
    private val mimeType: String?
        get() = requireArguments().getString(ARG_MIME_TYPE, null)
    private val hideToolbar: Boolean
        get() = requireArguments().getBoolean(ARG_HIDE_TOOLBAR, false)

    // Permissions
    private val permissionsChecker = PermissionsChecker(this, PermissionsUtils.mainPermissions)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }

            recyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            bottomInset = insets.bottom
            selectionSheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            // Content is the sole owner of the bottom inset. The sheet's
            // background may extend under navigation; its actions must not.
            selectionSheet.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = 0
            }
            view.findViewById<View>(R.id.selectionSheetContent).updatePadding(bottom = bottomInset)
            selectionTopBar.updatePadding(top = insets.top)
            updateSelectionGridPadding()
            updateSelectionPeekHeight()

            windowInsets
        }

        appBarLayout.isVisible = !hideToolbar
        selectionPreDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
            if (selectionActive) updateSelectionGridPadding()
            true
        }.also { view.viewTreeObserver.addOnPreDrawListener(it) }
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) = updateSelectionGridPadding()
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                selectionSheetHint.setText(if (newState == BottomSheetBehavior.STATE_EXPANDED)
                    R.string.selection_sheet_collapse else R.string.selection_sheet_expand)
                updateSelectionGridPadding()
            }
        })
        selectionSheet.addOnLayoutChangeListener { sheet, _, _, _, _, _, _, _, _ ->
            updateSelectionPeekHeight()
            // The behavior offsets the sheet after the view's layout callback.
            // Read its final position in the next frame, not the pre-offset top.
            sheet.post {
                if (view != null && selectionActive) updateSelectionGridPadding()
            }
        }
        selectionTopBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSelectionGridPadding() }
        selectionSheetHint.setOnClickListener {
            sheetBehavior.state = if (sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
                BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
        }
        view.findViewById<View>(R.id.selectionCancel).setOnClickListener { selectionTracker?.clearSelection() }
        selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { selectionTracker?.clearSelection() }
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }

        toolbar.setupWithNavController(findNavController())

        val context = requireContext()

        appBarLayout.statusBarForeground = MaterialShapeDrawable.createWithElevationOverlay(context)

        when (albumType) {
            AlbumType.TRASH -> R.menu.fragment_album_toolbar_trash

            else -> null
        }?.also {
            toolbar.inflateMenu(it)
        } ?: toolbar.menu.clear()

        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.emptyTrash -> {
                    val selection = thumbnailAdapter.currentList.mapNotNull {
                        AlbumViewModel.AlbumContent.MediaItem::class.safeCast(it)?.media
                    }.toTypedArray()
                    val count = selection.size

                    if (count > 0) {
                        MediaDialogsUtils.openDeleteForeverDialog(requireContext(), *selection) {
                            deleteForeverContract.launch(
                                requireContext().contentResolver.createDeleteRequest(
                                    *it.map { media -> media.uri }.toTypedArray()
                                )
                            )
                        }
                    }

                    true
                }

                else -> false
            }
        }

        recyclerView.layoutManager = ThumbnailLayoutManager(
            requireContext(), thumbnailAdapter
        )
        recyclerView.adapter = thumbnailAdapter
        (recyclerView as SelectionGestureRecyclerView).onGestureFinished = {
            if (pendingSelectionStart) {
                pendingSelectionStart = false
                // AndroidX Selection can miss ACTION_UP after a stationary long
                // press and leave gesture selection consuming later scrolls.
                // Clearing selection resets its gesture machinery; restore the
                // exact selected keys before showing the selection chrome.
                val tracker = selectionTracker
                val selected = tracker?.selection?.toList().orEmpty()
                if (tracker != null && selected.isNotEmpty()) {
                    restoringGestureSelection = true
                    try {
                        tracker.clearSelection()
                        selected.forEach { tracker.select(it) }
                    } finally {
                        restoringGestureSelection = false
                    }
                }
                updateSelection()
            }
        }

        thumbnailAdapter.setOnItemSelected { media ->
            // SelectionTracker owns clicks while a selection exists, including
            // a delayed click posted by the initiating long press.
            if (!intentsViewModel.isPicking.value && selectionTracker?.hasSelection() == true) return@setOnItemSelected
            if (intentsViewModel.isPicking.value) {
                selectionTracker?.select(media)
            } else {
                startActivity(
                    Intent(requireContext(), ViewActivity::class.java).apply {
                        action = MediaStore.ACTION_REVIEW
                        setDataAndTypeAndNormalize(media.uri, media.mimeType)
                        putExtras(
                            ViewActivity.createBundle(
                                albumType,
                                albumUri,
                                mediaType,
                                mimeType,
                            )
                        )
                    }
                )
            }
        }

        selectionTracker = SelectionTracker.Builder(
            "thumbnail-${albumUri}",
            recyclerView,
            thumbnailAdapter.itemKeyProvider,
            ThumbnailItemDetailsLookup(recyclerView),
            StorageStrategy.createParcelableStorage(Media::class.java),
        ).withSelectionPredicate(
            object : SelectionTracker.SelectionPredicate<Media>() {
                private val delegate = when (intentsViewModel.allowMultipleSelection.value) {
                    true -> SelectionPredicates.createSelectAnything<Media>()
                    false -> SelectionPredicates.createSelectSingleAnything<Media>()
                }

                override fun canSetStateForKey(key: Media, nextState: Boolean): Boolean {
                    if (key == ThumbnailAdapter.DATE_HEADER_KEY) return false
                    return delegate.canSetStateForKey(key, nextState)
                }

                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean {
                    val item = thumbnailAdapter.currentList.getOrNull(position)
                    if (item is AlbumViewModel.AlbumContent.DateHeader) return false
                    return delegate.canSetStateAtPosition(position, nextState)
                }

                override fun canSelectMultiple(): Boolean = delegate.canSelectMultiple()
            }
        ).build().also {
            thumbnailAdapter.selectionTracker = it
            it.addObserver(selectionTrackerObserver)
        }
        if (savedInstanceState != null) selectionTracker?.onRestoreInstanceState(savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsChecker.withPermissionsGranted {
                    loadData()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        thumbnailAdapter.isQuickSelectEnabled =
            PreferenceManager.getDefaultSharedPreferences(requireContext()).quickSelectDateEnabled
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectionTracker?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        selectionPreDrawListener?.let { view?.viewTreeObserver?.removeOnPreDrawListener(it) }
        selectionPreDrawListener = null
        pendingSelectionStart = false
        (recyclerView as SelectionGestureRecyclerView).onGestureFinished = null
        endSelectionMode()

        thumbnailAdapter.selectionTracker = null
        selectionTracker?.kill(thumbnailAdapter)
        selectionTracker = null

        recyclerView.layoutManager = null
        recyclerView.adapter = null

        thumbnailAdapter.setOnItemSelected(null)

        super.onDestroyView()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        recyclerView.layoutManager = ThumbnailLayoutManager(
            requireContext(), thumbnailAdapter
        )
    }

    private suspend fun loadData() {
        coroutineScope {
            launch {
                intentsViewModel.parsedIntent.collectLatest {
                    when (it) {
                        is IntentsViewModel.ParsedIntent.PickIntent -> {
                            viewModel.loadAlbum(
                                AlbumViewModel.AlbumRequest(
                                    albumType = albumType,
                                    albumUri = albumUri,
                                    mediaType = mediaType,
                                    mimeType = it.mimeType ?: mimeType,
                                )
                            )
                        }

                        else -> viewModel.loadAlbum(
                            AlbumViewModel.AlbumRequest(
                                albumType = albumType,
                                albumUri = albumUri,
                                mediaType = mediaType,
                                mimeType = mimeType,
                            )
                        )
                    }
                }
            }

            launch {
                viewModel.album.collectLatest {
                    when (it) {
                        is RequestStatus.Loading -> {
                            // Do nothing
                        }

                        is RequestStatus.Success -> {
                            val (album, medias) = it.data

                            val albumRequest = viewModel.albumRequest.value
                            when (albumRequest?.albumType) {
                                AlbumType.REELS -> when (albumRequest.mediaType) {
                                    MediaType.IMAGE -> R.string.album_photos
                                    MediaType.VIDEO -> R.string.album_videos
                                    else -> R.string.album_reels
                                }

                                AlbumType.FAVORITES -> R.string.album_favorites

                                AlbumType.TRASH -> R.string.album_trash

                                null -> null
                            }?.also { stringResId ->
                                toolbar.setTitle(stringResId)
                            } ?: run {
                                toolbar.title = album.name
                            }

                            thumbnailAdapter.submitList(medias)

                            val isEmpty = medias.isEmpty()
                            recyclerView.isVisible = !isEmpty
                            toolbar.menu.findItem(R.id.emptyTrash)?.isVisible = !isEmpty
                            noMediaLinearLayout.isVisible = isEmpty

                            if (albumRequest?.albumType == AlbumType.TRASH) {
                                warningTrashTimePeriod.isVisible = isEmpty
                                warningTrashTimePeriodToolbar.isVisible = !isEmpty
                            }
                        }

                        is RequestStatus.Error -> {
                            Log.e(LOG_TAG, "Failed to load album, error: ${it.error}")

                            toolbar.title = ""

                            thumbnailAdapter.submitList(listOf())

                            recyclerView.isVisible = false
                            toolbar.menu.findItem(R.id.emptyTrash)?.isVisible = false
                            noMediaLinearLayout.isVisible = true

                            if (it.error == MediaError.NOT_FOUND) {
                                // Get out of here
                                findNavController().navigateUp()
                            }
                        }
                    }
                }
            }

            launch {
                viewModel.inSelectionMode.collectLatest {
                    thumbnailAdapter.setInSelectionMode(it)

                }
            }
        }
    }

    private fun updateSelection() {
        if (restoringGestureSelection) return
        val count = selectionTracker?.selection?.count() ?: 0
        // Entering selection hides app chrome and resizes the grid. Do not move
        // the cells underneath the finger that initiated the long press.
        if (count > 0 && !selectionActive && (recyclerView as SelectionGestureRecyclerView).gestureInProgress) {
            pendingSelectionStart = true
            return
        }
        pendingSelectionStart = false
        viewModel.setInSelectionMode(count > 0)
        if (count == 0) {
            endSelectionMode()
            return
        }
        if (!selectionActive) startSelectionMode()
        selectionCount.text = java.text.NumberFormat.getIntegerInstance().format(count)
        selectionCount.contentDescription = resources.getQuantityString(R.plurals.thumbnail_selection_count, count, count)
        refreshCollageAvailability()
    }

    private fun startSelectionMode() {
        if (selectionActive) return
        selectionActive = true
        selectionBackCallback?.isEnabled = true
        // The Reels page lives beneath MainFragment's app bar. Remove only the app
        // chrome temporarily, so this fragment's background reaches the status bar.
        if (hideToolbar) {
            selectionParentAppBar = parentFragment?.view?.findViewById(R.id.appBarLayout)
            selectionParentAppBar?.let { parentAppBarWasVisible = it.isVisible; it.isVisible = false }
            selectionNavigationBar = activity?.findViewById(R.id.navigationBarView)
            selectionNavigationBar?.let { navigationBarWasVisible = it.isVisible; it.isVisible = false }
        } else {
            appBarLayout.isVisible = false
        }
        selectionTopBar.isVisible = true
        ViewCompat.requestApplyInsets(requireView())
        buildSelectionSheet()
    }

    private fun buildSelectionSheet() {
        selectionPrimary.removeAllViews()
        selectionMore.removeAllViews()
        val popup = PopupMenu(requireContext(), toolbar)
        popup.inflate(when (viewModel.albumRequest.value?.albumType) {
            AlbumType.TRASH -> R.menu.album_action_bar_trash
            else -> if (intentsViewModel.isPicking.value) R.menu.fragment_album_pick_action_bar
                else R.menu.fragment_album_action_bar
        })
        val menu = popup.menu
        val preferred = listOf(R.id.collage, R.id.share, R.id.moveToTrash,
            R.id.restoreFromTrash, R.id.deleteForever, R.id.done, R.id.toolbox, R.id.lockMedia)
        val items = (0 until menu.size()).map { menu.getItem(it) }
            .sortedBy { preferred.indexOf(it.itemId).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        val density = resources.displayMetrics.density
        val foreground = com.google.android.material.color.MaterialColors.getColor(
            selectionSheet, com.google.android.material.R.attr.colorOnSurface)
        items.forEach { item ->
            val cell = LinearLayout(requireContext()).apply {
                tag = item.itemId
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = (80 * density).toInt()
                setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
                isClickable = true
                isFocusable = true
                contentDescription = item.title
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onSelectionAction(item) }
            }
            cell.addView(ImageView(requireContext()).apply {
                setImageDrawable(item.icon?.mutate())
                imageTintList = android.content.res.ColorStateList.valueOf(foreground)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()))
            cell.addView(TextView(requireContext()).apply {
                text = if (item.itemId == R.id.moveToTrash) getString(R.string.delete) else item.title
                gravity = Gravity.CENTER
                maxLines = 2
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * density).toInt()
            })
            selectionPrimary.addView(cell,
                LinearLayout.LayoutParams((88 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        selectionMore.isVisible = false
        selectionSheetHint.isVisible = false
        sheetBehavior.isDraggable = false
        // Give the formerly GONE sheet a nonzero initial peek, then recalculate
        // after its newly populated children are measured. The behavior owns the
        // vertical placement; layout_gravity must not add another bottom offset.
        sheetBehavior.peekHeight = (128 * density).toInt()
        selectionSheet.isVisible = true
        selectionPrimary.doOnNextLayout {
            if (selectionActive && selectionSheet.isVisible) {
                updateSelectionPeekHeight()
                sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                updateSelectionGridPadding()
            }
        }
        selectionSheet.requestLayout()
        refreshCollageAvailability()
    }

    private fun refreshCollageAvailability() {
        val types = selectionTracker?.selection?.map { it.mediaType } ?: emptyList()
        val error = collageSelectionError(types)
        selectionPrimary.findViewWithTag<View>(R.id.collage)?.apply {
            isEnabled = error == null
            alpha = if (isEnabled) 1f else 0.45f
        }
        val hasCollage = selectionPrimary.findViewWithTag<View>(R.id.collage) != null
        selectionSheetReason.isVisible = hasCollage && error != null
        if (hasCollage && error != null) selectionSheetReason.setText(when (error) {
            CollageSelectionError.VIDEO -> R.string.collage_video_error
            CollageSelectionError.COUNT -> R.string.collage_count_error
        })
        updateSelectionPeekHeight()
    }

    private fun updateSelectionPeekHeight() {
        if (!selectionActive || !selectionSheet.isVisible) return
        val height = (if (selectionSheetHint.isVisible) selectionSheetHint.height else 0) +
            selectionPrimary.height + (if (selectionSheetReason.isVisible) selectionSheetReason.height else 0)
        // Behavior positions the collapsed sheet at parentHeight - peekHeight.
        // Include the content's navigation padding in the visible collapsed area.
        val peek = height + bottomInset
        if (height > 0 && sheetBehavior.peekHeight != peek) sheetBehavior.peekHeight = peek
        updateSelectionGridPadding()
    }

    private fun updateSelectionGridPadding() {
        if (view == null) return
        val covered = if (selectionActive && selectionSheet.isVisible && selectionSheet.isLaidOut) {
            val gridBounds = android.graphics.Rect()
            val sheetBounds = android.graphics.Rect()
            if (recyclerView.getGlobalVisibleRect(gridBounds) && selectionSheet.getGlobalVisibleRect(sheetBounds))
                (gridBounds.bottom - sheetBounds.top).coerceAtLeast(0) else 0
        } else 0
        val top = if (selectionActive) selectionTopBar.height else 0
        val bottom = maxOf(bottomInset, covered)
        // RecyclerView requests layout on setPadding even when values are the
        // same; avoid an endless pre-draw/layout loop while selection is active.
        if (recyclerView.paddingTop != top || recyclerView.paddingBottom != bottom) {
            recyclerView.updatePadding(top = top, bottom = bottom)
        }
    }

    private fun endSelectionMode() {
        if (!selectionActive) return
        selectionActive = false
        selectionBackCallback?.isEnabled = false
        selectionTopBar.isVisible = false
        selectionSheet.isVisible = false
        selectionNavigationBar?.isVisible = navigationBarWasVisible
        selectionNavigationBar = null
        selectionParentAppBar?.isVisible = parentAppBarWasVisible
        selectionParentAppBar = null
        appBarLayout.isVisible = !hideToolbar
        updateSelectionGridPadding()
        view?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun trashMedias(trash: Boolean, vararg medias: Media) {
        lastProcessedSelection = medias

        val contract = when (trash) {
            true -> trashContract
            false -> restoreFromTrashContract
        }

        contract.launch(
            requireContext().contentResolver.createTrashRequest(
                trash, *medias.map { it.uri }.toTypedArray()
            )
        )
    }

    /**
     * Set the activity result and close the activity.
     * @param medias The selected medias
     */
    private fun sendResult(vararg medias: Media) {
        val activity = activity ?: return
        val intent = activity.intent ?: return

        when (intent.action) {
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_PICK -> activity.setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    if (intentsViewModel.allowMultipleSelection.value) {
                        clipData = ClipData.newUri(
                            activity.contentResolver, "", medias.first().uri
                        ).also { clipData ->
                            for (media in 1 until medias.size) {
                                clipData.addItem(
                                    ClipData.Item(medias[media].uri)
                                )
                            }
                        }
                    } else {
                        require(medias.size == 1) {
                            "More than one media provided when only one was requested"
                        }

                        data = medias.first().uri
                    }

                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            )

            Intent.ACTION_SET_WALLPAPER -> {
                require(medias.size == 1) {
                    "More than one media provided when only one was requested"
                }

                runCatching {
                    wallpaperManager.getCropAndSetWallpaperIntent(
                        medias.first().uri
                    )
                }.getOrNull()?.also {
                    activity.startActivity(it)
                } ?: Toast.makeText(
                    activity,
                    R.string.intent_no_system_wallpaper_cropper_available,
                    Toast.LENGTH_LONG,
                ).show()
            }

            else -> throw Exception("Unknown action")
        }

        activity.finish()
    }

    companion object {
        private val LOG_TAG = AlbumFragment::class.simpleName!!

        private const val ARG_ALBUM_TYPE = "album_type"
        private const val ARG_ALBUM_URI = "album_uri"
        private const val ARG_MEDIA_TYPE = "media_type"
        private const val ARG_MIME_TYPE = "mime_type"
        private const val ARG_HIDE_TOOLBAR = "hide_toolbar"

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         * @param albumType The [AlbumType] to display, null to use [albumUri]
         * @param albumUri The [Album] to display's bucket ID, if null, reels will be shown
         * @param fileType The [MediaType] to filter for
         * @param mimeType The MIME type to filter for
         */
        fun createBundle(
            albumType: AlbumType? = null,
            albumUri: Uri? = null,
            fileType: MediaType? = null,
            mimeType: String? = null,
            hideToolbar: Boolean = false,
        ) = bundleOf(
            ARG_ALBUM_TYPE to albumType,
            ARG_ALBUM_URI to albumUri,
            ARG_MEDIA_TYPE to fileType,
            ARG_MIME_TYPE to mimeType,
            ARG_HIDE_TOOLBAR to hideToolbar,
        )
    }
}
