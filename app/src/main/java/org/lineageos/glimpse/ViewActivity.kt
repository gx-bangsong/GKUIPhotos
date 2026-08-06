/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.KeyguardManager
import android.app.KeyguardManager.KeyguardDismissCallback
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.view.updateLayoutParams
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
            finish()
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
                    val isGif = it.mediaType == MediaType.IMAGE && (
                        it.mimeType.contains("gif", ignoreCase = true) ||
                            (it.displayName?.endsWith(".gif", ignoreCase = true) == true)
                        )
                    when {
                        isGif -> startActivity(GifEditorActivity.createIntent(this@ViewActivity, it.uri))

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

        // In PiP we hide the chrome (toolbar + bottom sheet + toolbox) to maximise the
        // video surface and avoid the toolbox overlapping the player controls;
        // restoring brings it back. Also exit fullscreen chrome so controls don't
        // stay hidden after PiP.
        appBarLayout.fade(!isInPictureInPictureMode)
        bottomSheetLinearLayout.fade(!isInPictureInPictureMode)
        toolboxButton.fade(!isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            // Don't keep the custom fullscreen mode active inside the tiny PiP window
            // — the system chrome is already minimal and the play/pause button would
            // otherwise be hidden behind our fullscreen fade.
            if (viewModel.fullscreenMode.value) {
                viewModel.toggleFullscreenMode()
            }
        } else {
            updateToolboxVisibility()
            updateSheetsHeight()
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
                }
            }

            launch {
                viewModel.fullscreenMode.collectLatest { fullscreenMode ->
                    appBarLayout.fade(!fullscreenMode)
                    bottomSheetLinearLayout.fade(!fullscreenMode)
                    updateToolboxVisibility()

                    window.setBarsVisibility(systemBars = !fullscreenMode)

                    // If the sheets are being made visible again, update the values
                    if (!fullscreenMode) {
                        updateSheetsHeight()
                    }
                }
            }

            launch {
                viewModel.displayedMedia.collectLatest { displayedMedia ->
                    // Update date and time text
                    displayedMedia?.also {
                        toolbar.title = dateFormatter.format(it.dateModified)
                        toolbar.subtitle = timeFormatter.format(it.dateModified)
                    } ?: run {
                        toolbar.title = ""
                        toolbar.subtitle = ""
                    }

                    // Update favorite button
                    val isFavorite = displayedMedia?.isFavorite ?: false
                    favoriteButton.isSelected = isFavorite
                    favoriteButton.setText(
                        when (isFavorite) {
                            true -> R.string.file_action_remove_from_favorites
                            false -> R.string.file_action_add_to_favorites
                        }
                    )

                    // Update info button
                    infoButton.isVisible = displayedMedia != null

                    // Update edit button: show "Edit GIF" for GIFs so the dedicated
                    // GIF editor (module 7) is discoverable; still routes through
                    // the same adjustButton.
                    // Keep the icon visually unified with the other three bottom
                    // buttons (all use 24dp filled Material icons via drawableTop).
                    // Only the text changes to avoid the previous ic_video_to_gif
                    // mismatch that made the edit icon look heavier/thinner than
                    // share/star/delete.
                    displayedMedia?.let {
                        val isGif = it.mediaType == MediaType.IMAGE && (
                            it.mimeType.contains("gif", ignoreCase = true) ||
                                (it.displayName?.endsWith(".gif", ignoreCase = true) == true)
                            )
                        if (isGif) {
                            adjustButton.setText(R.string.gif_editor_title)
                        } else {
                            adjustButton.setText(R.string.file_action_edit)
                        }
                        // Ensure the top drawable stays the consistent ic_edit
                        // (setCompoundDrawables, not setIconResource which would
                        // add a start-icon and break the BottomSheet.Button style).
                        adjustButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_edit, 0, 0)
                    }

                    // Update toolbox capsule: shown only for images (for videos it
                    // would overlap the player's progress bar; videos reach the
                    // toolbox via the toolbar overflow menu instead).
                    updateToolboxVisibility()

                    // Update delete button
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

                    // Reset motion photo toggle button
                    viewModel.toggleMotionPhotoEnabled(false)
                }
            }

            launch {
                viewModel.displayedMediaToMotionPhoto.collectLatest { (displayedMedia, motionPhoto) ->
                    // Update ExoPlayer
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

                    // Trigger a sheets height update
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
                    // Update use as button
                    useAsButton.isVisible = !secure
                }
            }

            launch {
                viewModel.readOnly.collectLatest { readOnly ->
                    // Update favorite button
                    favoriteButton.isVisible = !readOnly

                    // Update adjust button — for GIFs keep editing available even
                    // when readOnly (VIEW intents) so the offline GIF editor is
                    // discoverable; it will "Save as new" in that case.
                    val media = viewModel.displayedMedia.value
                    val isGif = media?.mediaType == MediaType.IMAGE && (
                        media.mimeType.contains("gif", ignoreCase = true) ||
                            (media.displayName?.endsWith(".gif", ignoreCase = true) == true)
                        )
                    adjustButton.isVisible = !readOnly || isGif

                    // Update delete button
                    deleteButton.isVisible = !readOnly

                    // Module 6: toolbox capsule is available whenever editing is
                    // allowed, but only for images (videos use the overflow menu).
                    // Keep it available for GIFs even in readOnly so the GIF editor
                    // entry point isn't lost.
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

            // Make sure we will forcefully reload and restart the video
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

        val top = appBarLayout.measuredHeight
        val bottom = bottomSheetLinearLayout.measuredHeight
        viewModel.setSheetsHeight(top, bottom)

        // Keep the floating toolbox capsule just above the bottom sheet so it
        // never overlaps the PlayerView's controls (which are inset by the same
        // bottom height) and never collides with the fullscreen toggle area.
        toolboxButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = bottom + 16.dpToPx(this@ViewActivity)
        }
    }

    private fun Int.dpToPx(context: android.content.Context): Int =
        (this * context.resources.displayMetrics.density).toInt()

    /**
     * Module 6: single source of truth for the floating toolbox capsule. It is
     * shown only for images (a video would otherwise be covered by it over the
     * progress bar), only when editing is allowed, hidden in fullscreen and in
     * PiP (where the window is tiny and the play button would be covered).
     * Videos reach the toolbox via the toolbar overflow menu.
     */
    private fun updateToolboxVisibility() {
        if (isInPipMode()) {
            toolboxButton.isVisible = false
            return
        }
        val media = viewModel.displayedMedia.value
        val isGif = media?.mediaType == MediaType.IMAGE && (
            media.mimeType.contains("gif", ignoreCase = true) ||
                (media.displayName?.endsWith(".gif", ignoreCase = true) == true)
            )
        // For GIFs keep the toolbox reachable even when readOnly so the GIF
        // editor entry point isn't lost in VIEW intents.
        val canEdit = !viewModel.readOnly.value || isGif
        toolboxButton.isVisible = media != null &&
            media.mediaType != MediaType.VIDEO &&
            canEdit &&
            !viewModel.fullscreenMode.value
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

    companion object {
        private val LOG_TAG = ViewActivity::class.simpleName!!

        private val dateFormatter = SimpleDateFormat.getDateInstance()
        private val timeFormatter = SimpleDateFormat.getTimeInstance()

        val EXTRA_ALBUM_TYPE = "${ViewActivity::class.qualifiedName}.album_type"
        val EXTRA_ALBUM_URI = "${ViewActivity::class.qualifiedName}.album_uri"
        val EXTRA_MEDIA_TYPE = "${ViewActivity::class.qualifiedName}.media_type"
        val EXTRA_MIME_TYPE = "${ViewActivity::class.qualifiedName}.mime_type"

        /**
         * Create a [Bundle] to use as the extras for this activity.
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
        ) = bundleOf(
            EXTRA_ALBUM_TYPE to albumType,
            EXTRA_ALBUM_URI to albumUri,
            EXTRA_MEDIA_TYPE to fileType,
            EXTRA_MIME_TYPE to mimeType,
        )
    }
}
