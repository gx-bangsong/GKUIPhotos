/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.PendingIntent
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.glimpse.datasources.MediaError
import org.lineageos.glimpse.ext.buildEditIntent
import org.lineageos.glimpse.ext.buildShareIntent
import org.lineageos.glimpse.ext.buildUseAsIntent
import org.lineageos.glimpse.ext.createDeleteRequest
import org.lineageos.glimpse.ext.createFavoriteRequest
import org.lineageos.glimpse.ext.createTrashRequest
import org.lineageos.glimpse.ext.fade
import org.lineageos.glimpse.ext.setBarsVisibility
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.AlbumType
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.MotionPhoto
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.ui.dialogs.ImageToolboxBottomSheet
import org.lineageos.glimpse.ui.dialogs.MediaInfoBottomSheetDialog
import org.lineageos.glimpse.ui.dialogs.SharePrivacyBottomSheet
import org.lineageos.glimpse.ui.recyclerview.MediaViewerAdapter
import org.lineageos.glimpse.utils.MediaDialogsUtils
import org.lineageos.glimpse.utils.PermissionsChecker
import org.lineageos.glimpse.utils.PermissionsUtils
import org.lineageos.glimpse.viewmodels.IntentsViewModel
import org.lineageos.glimpse.viewmodels.IntentsViewModel.ParsedIntent
import org.lineageos.glimpse.viewmodels.LocalPlayerViewModel
import java.text.SimpleDateFormat

/**
 * An activity used to view one or mode medias.
 */
class ViewActivity : AppCompatActivity(R.layout.activity_view) {
    // View models
    private val viewModel by viewModels<LocalPlayerViewModel>()
    private val intentsViewModel by viewModels<IntentsViewModel>()

    // Views
    private val adjustButton by lazy { findViewById<MaterialButton>(R.id.adjustButton) }
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val bottomSheetLinearLayout by lazy { findViewById<LinearLayout>(R.id.bottomSheetLinearLayout) }
    private val deleteButton by lazy { findViewById<MaterialButton>(R.id.deleteButton) }
    private val favoriteButton by lazy { findViewById<MaterialButton>(R.id.favoriteButton) }
    private val infoButton by lazy { toolbar.menu.findItem(R.id.info) }
    private val motionPhotoToggleButton by lazy { findViewById<MaterialButton>(R.id.motionPhotoToggleButton) }
    private val shareButton by lazy { findViewById<MaterialButton>(R.id.shareButton) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val toolboxButton by lazy { findViewById<MaterialButton>(R.id.toolboxButton) }
    private val useAsButton by lazy { toolbar.menu.findItem(R.id.useAs) }
    private val viewPager by lazy { findViewById<ViewPager2>(R.id.viewPager) }

    // System services
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    private var lastVideoUriPlayed: Uri? = null

    // PiP native playback controls
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY -> viewModel.play()
                ACTION_PIP_PAUSE -> viewModel.pause()
            }
            // Refresh actions after state change
            if (isInPipMode()) {
                updatePipParams(viewModel.isPlaying.value)
            }
        }
    }

    // Adapter
    private val mediaViewerAdapter by lazy {
        MediaViewerAdapter(
            localPlayerViewModel = viewModel,
            onNavigate = { forward ->
                viewPager.adapter?.let { adapter ->
                    val currentPosition = viewPager.currentItem

                    val newPosition = if (forward) {
                        currentPosition + 1
                    } else {
                        currentPosition - 1
                    }

                    if (newPosition in 0 until adapter.itemCount) {
                        viewPager.setCurrentItem(newPosition, true)
                    }
                }
            },
        )
    }

    private var lastProcessedMedia: Media? = null

    // Contracts
    private val deleteUriContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != RESULT_CANCELED

            MediaDialogsUtils.showDeleteForeverResultSnackbar(
                this,
                bottomSheetLinearLayout,
                succeeded, 1,
                bottomSheetLinearLayout,
            )
        }

    private val trashUriContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != RESULT_CANCELED

            MediaDialogsUtils.showMoveToTrashResultSnackbar(
                this,
                bottomSheetLinearLayout,
                succeeded, 1,
                bottomSheetLinearLayout,
                lastProcessedMedia?.let { trashedMedia ->
                    { trashMedia(trashedMedia, false) }
                },
            )

            lastProcessedMedia = null
        }

    private val restoreUriFromTrashContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != RESULT_CANCELED

            MediaDialogsUtils.showRestoreFromTrashResultSnackbar(
                this,
                bottomSheetLinearLayout,
                succeeded, 1,
                bottomSheetLinearLayout,
                lastProcessedMedia?.let { trashedMedia ->
                    { trashMedia(trashedMedia, true) }
                },
            )
        }

    private val favoriteContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // Do nothing
        }

    private val onPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)

            this@ViewActivity.viewModel.setMediaPosition(position)
        }
    }

    private val mediaInfoBottomSheetDialogCallbacks = MediaInfoBottomSheetDialog.Callbacks(this)

    // Intents
    private val intentListener = Consumer<Intent> { intentsViewModel.onIntent(it) }

    // Permissions
    private val permissionsChecker = PermissionsChecker(this, PermissionsUtils.mainPermissions)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Fix: from PiP return then back should go to MainActivity, not exit to launcher.
        // If ViewActivity is task root (MainActivity was destroyed or PiP moved task),
        // navigate to MainActivity explicitly.
        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }

        // Register PiP action receiver
        runCatching {
            val filter = IntentFilter().apply {
                addAction(ACTION_PIP_PLAY)
                addAction(ACTION_PIP_PAUSE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipActionReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(pipActionReceiver, filter)
            }
        }

        // We only want to show this activity on top of the keyguard if we're being launched with
        // the ACTION_REVIEW_SECURE intent and the system is currently locked.
        if (keyguardManager.isKeyguardLocked && intent.action == MediaStore.ACTION_REVIEW_SECURE) {
            setShowWhenLocked(true)
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomSheetLinearLayout) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            // Avoid updating the sheets height when they're hidden.
            // Once the system bars will be made visible again, this function
            // will be called again.
            if (!viewModel.fullscreenMode.value) {
                bottomSheetLinearLayout.updatePadding(
                    left = insets.left,
                    right = insets.right,
                    bottom = insets.bottom
                )

                updateSheetsHeight()
            }

            windowInsets
        }

        // Attach the adapter to the view pager
        viewPager.adapter = mediaViewerAdapter

        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.info -> {
                    viewModel.displayedMedia.value?.let {
                        MediaInfoBottomSheetDialog(
                            this@ViewActivity,
                            it,
                            mediaInfoBottomSheetDialogCallbacks,
                            viewModel.secure.value,
                        ).show()
                    }
                    true
                }

                R.id.useAs -> {
                    viewModel.displayedMedia.value?.let {
                        startActivity(Intent.createChooser(buildUseAsIntent(it), null))
                    }
                    true
                }

                R.id.toolbox -> {
                    // Overflow-menu entry point for the toolbox; for videos this is
                    // the only entry, since the floating capsule is hidden for
                    // videos so it can't cover the progress bar.
                    viewModel.displayedMedia.value?.let {
                        ImageToolboxBottomSheet(this@ViewActivity, this@ViewActivity, it).show()
                    }
                    true
                }

                else -> false
            }
        }

        toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }

        favoriteButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                favoriteContract.launch(
                    contentResolver.createFavoriteRequest(
                        !it.isFavorite, it.uri
                    )
                )
            }
        }

        shareButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    // Module 2: for images, offer EXIF stripping before sharing.
                    if (it.mediaType == MediaType.IMAGE) {
                        SharePrivacyBottomSheet(
                            this@ViewActivity,
                            this@ViewActivity,
                            it,
                        ).show()
                    } else {
                        startActivity(
                            Intent.createChooser(
                                buildShareIntent(it),
                                null
                            )
                        )
                    }
                }
            }
        }

        adjustButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    // Modules 4/5/7: route editing through Glimpse's own offline
                    // editors — the full GIF editor for animated GIFs, the image
                    // editor (rotate/filter/crop/ID-photo) for still images, and
                    // the legacy external chooser for videos.
                    when {
                        it.mediaType == MediaType.IMAGE && it.mimeType.equals(
                            "image/gif", ignoreCase = true
                        ) -> startActivity(GifEditorActivity.createIntent(this@ViewActivity, it.uri))

                        it.mediaType == MediaType.IMAGE -> startActivity(
                            ImageEditorActivity.createIntent(this@ViewActivity, it.uri, it.mimeType)
                        )

                        else -> startActivity(Intent.createChooser(buildEditIntent(it), null))
                    }
                }
            }
        }

        // Module 6: offline image/video toolbox.
        toolboxButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                ImageToolboxBottomSheet(this@ViewActivity, this@ViewActivity, it).show()
            }
        }

        deleteButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    trashMedia(it)
                }
            }
        }

        deleteButton.setOnLongClickListener {
            viewModel.displayedMedia.value?.let {
                MediaDialogsUtils.openDeleteForeverDialog(this, it.uri) { uris ->
                    deleteUriContract.launch(contentResolver.createDeleteRequest(*uris))
                }

                true
            }

            false
        }

        motionPhotoToggleButton.setOnClickListener {
            viewModel.toggleMotionPhotoEnabled()
        }

        viewPager.offscreenPageLimit = 2
        viewPager.registerOnPageChangeCallback(onPageChangeCallback)

        intentListener.accept(intent)
        addOnNewIntentListener(intentListener)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsChecker.withPermissionsGranted {
                    loadData()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.play()
    }

    override fun onPause() {
        saveCurrentVideoPosition()

        // Module 1: when leaving while a video is playing (e.g. navigating home),
        // let onUserLeaveHint() take us into PiP rather than pausing immediately.
        if (!isInPipMode() && shouldEnterPip()) {
            // Don't pause; PiP will keep the video playing.
        } else {
            viewModel.pause()
        }

        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        // Module 1: auto-enter Picture-in-Picture when the user backgrounds the
        // activity while a video is actively playing.
        if (shouldEnterPip()) {
            enterPipIfNeeded()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        viewModel.setPictureInPictureMode(isInPictureInPictureMode)

        // In PiP we hide the chrome (toolbar + bottom sheet) to maximise the
        // video surface; restoring brings it back. Toolbox is handled by
        // updateToolboxVisibility() single source to avoid covering progress bar.
        appBarLayout.fade(!isInPictureInPictureMode)
        bottomSheetLinearLayout.fade(!isInPictureInPictureMode)
        updateToolboxVisibility()

        if (isInPictureInPictureMode) {
            // Ensure native PiP actions (play/pause) are shown
            updatePipParams(viewModel.isPlaying.value)
        }
    }

    /**
     * Module 1: whether the current state is eligible to enter PiP (a video is
     * actively playing on a supported Android version).
     */
    private fun shouldEnterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
            )
        ) return false
        val media = viewModel.displayedMedia.value ?: return false
        return media.mediaType == MediaType.VIDEO && viewModel.isPlaying.value
    }

    private fun isInPipMode() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    private fun buildPipActions(isPlaying: Boolean): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val actions = mutableListOf<RemoteAction>()
        return try {
            if (isPlaying) {
                val pauseIntent = Intent(ACTION_PIP_PAUSE).setPackage(packageName)
                val pausePending = PendingIntent.getBroadcast(
                    this, 1, pauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val icon = Icon.createWithResource(this, android.R.drawable.ic_media_pause)
                actions.add(
                    RemoteAction(
                        icon,
                        "Pause",
                        "Pause video",
                        pausePending
                    )
                )
            } else {
                val playIntent = Intent(ACTION_PIP_PLAY).setPackage(packageName)
                val playPending = PendingIntent.getBroadcast(
                    this, 2, playIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val icon = Icon.createWithResource(this, android.R.drawable.ic_media_play)
                actions.add(
                    RemoteAction(
                        icon,
                        "Play",
                        "Play video",
                        playPending
                    )
                )
            }
            actions
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun updatePipParams(isPlaying: Boolean = viewModel.isPlaying.value) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val builder = PictureInPictureParams.Builder()

            val media = viewModel.displayedMedia.value
            if (media != null && media.width > 0 && media.height > 0) {
                val rational = Rational(media.width, media.height)
                runCatching { builder.setAspectRatio(rational) }
            }

            val playerView = viewPager.findViewById<View>(
                resources.getIdentifier("playerView", "id", packageName)
            )
            if (playerView != null && playerView.isShown) {
                val bounds = Rect()
                playerView.getGlobalVisibleRect(bounds)
                builder.setSourceRectHint(bounds)
            }

            // Native PiP playback controls
            val actions = buildPipActions(isPlaying)
            if (actions.isNotEmpty()) {
                builder.setActions(actions)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
                builder.setSeamlessResizeEnabled(true)
            }

            setPictureInPictureParams(builder.build())
        } catch (_: Exception) {
            // Best effort
        }
    }

    private fun enterPipIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val builder = PictureInPictureParams.Builder()

        // Use the video aspect ratio when known for a properly sized window.
        val media = viewModel.displayedMedia.value
        if (media != null && media.width > 0 && media.height > 0) {
            val rational = Rational(media.width, media.height)
            runCatching { builder.setAspectRatio(rational) }
        }

        // Source rect hint for a smooth zoom transition into the PiP window.
        val playerView = viewPager.findViewById<View>(
            resources.getIdentifier("playerView", "id", packageName)
        )
        if (playerView != null && playerView.isShown) {
            val bounds = Rect()
            playerView.getGlobalVisibleRect(bounds)
            builder.setSourceRectHint(bounds)
        }

        // Native controls
        val actions = buildPipActions(viewModel.isPlaying.value)
        if (actions.isNotEmpty()) {
            builder.setActions(actions)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }

        runCatching {
            enterPictureInPictureMode(builder.build())
        }
    }

    override fun onDestroy() {
        saveCurrentVideoPosition()
        removeOnNewIntentListener(intentListener)
        viewPager.unregisterOnPageChangeCallback(onPageChangeCallback)
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSheetsHeight()
    }

    private suspend fun loadData() {
        coroutineScope {
            launch {
                intentsViewModel.parsedIntent.collectLatest { parsedIntent ->
                    parsedIntent?.handle {
                        when (it) {
                            is ParsedIntent.ViewIntent,
                            is ParsedIntent.ReviewIntent,
                            is ParsedIntent.SecureReviewIntent -> {
                                viewModel.setParsedIntent(it)
                            }

                            else -> run {
                                Toast.makeText(
                                    this@ViewActivity,
                                    R.string.intent_action_not_supported,
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        }

                    }
                }
            }

            launch {
                viewModel.mediasWithInitialPosition.collect {
                    when (it) {
                        is RequestStatus.Loading -> {
                            // Do nothing
                        }

                        is RequestStatus.Success -> {
                            val (medias, initialPosition) = it.data

                            mediaViewerAdapter.submitList(medias)

                            initialPosition?.let { position ->
                                viewPager.setCurrentItem(position, false)
                                onPageChangeCallback.onPageSelected(position)

                                viewModel.setMediaPosition(position)
                            }

                            if (medias.isEmpty()) {
                                // Get out of here
                                finish()
                            }
                        }

                        is RequestStatus.Error -> {
                            Log.e(LOG_TAG, "Failed to load medias, error: ${it.error}")

                            mediaViewerAdapter.submitList(listOf())

                            if (it.error == MediaError.NOT_FOUND) {
                                // Get out of here
                                finish()
                            }
                        }
                    }
                }
            }

            launch {
                viewModel.isPlaying.collectLatest { isPlaying ->
                    viewPager.keepScreenOn = isPlaying
                    if (isInPipMode()) {
                        updatePipParams(isPlaying)
                    }
                }
            }

            launch {
                viewModel.fullscreenMode.collectLatest { fullscreenMode ->
                    val inPip = viewModel.isInPictureInPictureMode.value
                    if (!inPip) {
                        appBarLayout.fade(!fullscreenMode)
                        bottomSheetLinearLayout.fade(!fullscreenMode)
                    }
                    updateToolboxVisibility()
                    window.setBarsVisibility(systemBars = !fullscreenMode && !inPip)
                    if (!fullscreenMode && !inPip) {
                        updateSheetsHeight()
                    }
                }
            }

            launch {
                viewModel.isInPictureInPictureMode.collectLatest { inPip ->
                    if (inPip) {
                        appBarLayout.fade(false)
                        bottomSheetLinearLayout.fade(false)
                        updatePipParams(viewModel.isPlaying.value)
                    } else {
                        val fullscreen = viewModel.fullscreenMode.value
                        appBarLayout.fade(!fullscreen)
                        bottomSheetLinearLayout.fade(!fullscreen)
                        window.setBarsVisibility(systemBars = !fullscreen)
                        if (!fullscreen) {
                            updateSheetsHeight()
                        }
                    }
                    updateToolboxVisibility()
                }
            }

            launch {
                viewModel.displayedMedia.collectLatest { displayedMedia ->
                    displayedMedia?.also {
                        toolbar.title = dateFormatter.format(it.dateModified)
                        toolbar.subtitle = timeFormatter.format(it.dateModified)
                    } ?: run {
                        toolbar.title = ""
                        toolbar.subtitle = ""
                    }

                    val isFavorite = displayedMedia?.isFavorite ?: false
                    favoriteButton.isSelected = isFavorite
                    favoriteButton.setText(
                        when (isFavorite) {
                            true -> R.string.file_action_remove_from_favorites
                            false -> R.string.file_action_add_to_favorites
                        }
                    )

                    infoButton.isVisible = displayedMedia != null
                    updateToolboxVisibility()

                    val isTrashed = displayedMedia?.isTrashed ?: false
                    deleteButton.text = when (isTrashed) {
                        true -> getString(R.string.file_action_restore_from_trash)
                        false -> getString(R.string.file_action_move_to_trash)
                    }
                    deleteButton.setCompoundDrawablesWithIntrinsicBounds(
                        0,
                        when (isTrashed) {
                            true -> R.drawable.ic_restore_from_trash
                            false -> R.drawable.ic_delete
                        },
                        0,
                        0
                    )

                    viewModel.toggleMotionPhotoEnabled(false)
                }
            }

            launch {
                viewModel.displayedMediaToMotionPhoto.collectLatest { (displayedMedia, motionPhoto) ->
                    displayedMedia?.let {
                        updateExoPlayer(it, motionPhoto)
                    }

                    val isPlayingMotionPhoto = motionPhoto != null
                    motionPhotoToggleButton.isSelected = isPlayingMotionPhoto
                    motionPhotoToggleButton.setText(
                        when (isPlayingMotionPhoto) {
                            true -> R.string.motion_photo_show_photo
                            false -> R.string.motion_photo_show_video
                        }
                    )

                    updateSheetsHeight()
                }
            }

            launch {
                viewModel.motionPhoto.collectLatest { motionPhoto ->
                    motionPhotoToggleButton.isVisible = motionPhoto != null
                }
            }

            launch {
                viewModel.secure.collectLatest { secure ->
                    useAsButton.isVisible = !secure
                }
            }

            launch {
                viewModel.readOnly.collectLatest { readOnly ->
                    favoriteButton.isVisible = !readOnly
                    adjustButton.isVisible = !readOnly
                    deleteButton.isVisible = !readOnly
                    updateToolboxVisibility()
                }
            }
        }
    }

    /**
     * Update exoPlayer's status.
     * @param media The currently displayed [Media]
     */
    private fun updateExoPlayer(media: Media, motionPhoto: MotionPhoto?) {
        if (media.mediaType == MediaType.VIDEO) {
            if (media.uri != lastVideoUriPlayed) {
                saveCurrentVideoPosition()
                lastVideoUriPlayed = media.uri
                viewModel.setCurrentVideoUri(media.uri)
            }
        } else {
            saveCurrentVideoPosition()
            motionPhoto?.also(viewModel::playMotionPhoto) ?: viewModel.stop()
            lastVideoUriPlayed = null
        }
    }

    private fun saveCurrentVideoPosition() {
        viewModel.saveCurrentVideoPosition(lastVideoUriPlayed)
    }

    private fun trashMedia(media: Media, trash: Boolean = !media.isTrashed) {
        if (trash) {
            lastProcessedMedia = media
        }

        val contract = when (trash) {
            true -> trashUriContract
            false -> restoreUriFromTrashContract
        }

        contract.launch(
            contentResolver.createTrashRequest(
                trash, media.uri
            )
        )
    }

    private fun updateSheetsHeight() {
        appBarLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        bottomSheetLinearLayout.measure(
            View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED
        )

        viewModel.setSheetsHeight(
            appBarLayout.measuredHeight,
            bottomSheetLinearLayout.measuredHeight,
        )
        // After sheets height changes, reposition toolbox to avoid covering
        // progress bar for videos.
        updateToolboxPosition()
    }

    /**
     * Module 6: single source of truth for the floating toolbox capsule.
     * NEW HCI: the capsule is visible for BOTH images and videos, providing
     * quick access to compress/convert/video->GIF. It is hidden only in
     * fullscreen and PiP to maximise surface, and positioned above the
     * player progress bar for videos to avoid covering it.
     * The overflow menu (toolbar) remains as a secondary entry point.
     */
    private fun updateToolboxVisibility() {
        val media = viewModel.displayedMedia.value
        val shouldShow = media != null &&
            !viewModel.readOnly.value &&
            !viewModel.fullscreenMode.value &&
            !viewModel.isInPictureInPictureMode.value
        toolboxButton.isVisible = shouldShow
        if (shouldShow) {
            updateToolboxPosition()
        }
    }

    private fun updateToolboxPosition() {
        val media = viewModel.displayedMedia.value
        val bottomHeight = viewModel.sheetsHeight.value.second
        val density = resources.displayMetrics.density

        // For images: original 124dp works (above bottom sheet).
        // For videos: need to be above the Exo controller + progress bar,
        // so add extra offset.
        val extraMarginDp = when (media?.mediaType) {
            MediaType.VIDEO -> 180 // above progress bar + controller
            else -> 124
        }
        // If bottom sheet height is already measured, ensure we stay above it
        // but not excessively high. Use max of calculated and bottomHeight+ extra.
        val targetMarginPx = (extraMarginDp * density).toInt()

        (toolboxButton.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            // Keep existing left/right, only adjust bottom
            if (lp.bottomMargin != targetMarginPx) {
                lp.bottomMargin = targetMarginPx
                toolboxButton.layoutParams = lp
            }
        }
    }

    private fun dismissKeyguardAndRun(runnable: () -> Unit) {
        if (!keyguardManager.isKeyguardLocked) {
            runnable()
            return
        }

        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    runnable()
                }
            }
        )
    }

    private fun handleBackNavigation() {
        if (isTaskRoot) {
            // Task root after PiP return: launch MainActivity instead of exiting to launcher
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
        }
        finish()
    }

    companion object {
        private val LOG_TAG = ViewActivity::class.simpleName!!

        private val dateFormatter = SimpleDateFormat.getDateInstance()
        private val timeFormatter = SimpleDateFormat.getTimeInstance()

        const val ACTION_PIP_PLAY = "org.lineageos.glimpse.action.PIP_PLAY"
        const val ACTION_PIP_PAUSE = "org.lineageos.glimpse.action.PIP_PAUSE"

        val EXTRA_ALBUM_TYPE = "${ViewActivity::class.qualifiedName}.album_type"
        val EXTRA_ALBUM_URI = "${ViewActivity::class.qualifiedName}.album_uri"
        val EXTRA_MEDIA_TYPE = "${ViewActivity::class.qualifiedName}.media_type"
        val EXTRA_MIME_TYPE = "${ViewActivity::class.qualifiedName}.mime_type"

        fun createBundle(
            albumType: AlbumType? = null,
            albumUri: Uri? = null,
            fileType: MediaType? = null,
            mimeType: String? = null,
        ) = bundleOf(
            EXTRA_ALBUM_TYPE to albumType,
            EXTRA_ALBUM_URI to albumUri,
            EXTRA_MEDIA_TYPE to fileType,
            EXTRA_MIME_TYPE to mimeType,
        )
    }
}
