/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.fragments.idphoto.IdPhotoCatalogFragment
import org.lineageos.glimpse.fragments.idphoto.IdPhotoFramingFragment
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.utils.media.IdPhotoGenerator
import org.lineageos.glimpse.viewmodels.IdPhotoViewModel
import org.lineageos.glimpse.viewmodels.IdPhotoWizardStep

/**
 * ID Photo Tool Activity.
 * Full-screen wizard hosting the multi-step ID photo creation workflow:
 * Step 1: Catalog selector (preset cards, group chips)
 * Step 2: Framing surface (locked ratio, rule-of-thirds, face oval, same-ratio rail)
 * [INSERTION POINT - Slice 2]: Background replacement step
 * [INSERTION POINT - Slice 3 / P2]: Print sheet tiling step
 */
class IdPhotoActivity : AppCompatActivity(R.layout.activity_id_photo) {

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val savingOverlay by lazy { findViewById<View>(R.id.savingOverlay) }

    val viewModel: IdPhotoViewModel by viewModels()

    companion object {
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        private const val TAG_CATALOG = "tag_catalog"
        private const val TAG_FRAMING = "tag_framing"

        fun createIntent(context: Context, uri: Uri, mimeType: String? = null) =
            Intent(context, IdPhotoActivity::class.java).apply {
                if (mimeType != null) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                putExtra(EXTRA_MEDIA_URI, uri)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val rootLayout = findViewById<View>(R.id.idPhotoRootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBars.bottom)
            insets
        }

        if (viewModel.sourceUri == null) {
            val uri: Uri? = intent.data ?: intent.getParcelableExtra(EXTRA_MEDIA_URI)
            if (uri == null) {
                finish()
                return
            }
            viewModel.sourceUri = uri
        }

        viewModel.ensurePreviewBitmapLoaded(contentResolver)

        toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }

        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val topFragment = supportFragmentManager.findFragmentById(R.id.idPhotoFragmentContainer)
            if (topFragment is IdPhotoCatalogFragment) {
                viewModel.setStep(IdPhotoWizardStep.CATALOG)
            } else if (topFragment is IdPhotoFramingFragment) {
                viewModel.setStep(IdPhotoWizardStep.FRAMING)
            }
            updateToolbar()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedPreset.collectLatest {
                    updateToolbar()
                }
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.idPhotoFragmentContainer, IdPhotoCatalogFragment(), TAG_CATALOG)
            }
        } else {
            updateToolbar()
        }
    }

    private fun handleBackNavigation() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
    }

    fun navigateToFraming() {
        viewModel.setStep(IdPhotoWizardStep.FRAMING)
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.idPhotoFragmentContainer, IdPhotoFramingFragment(), TAG_FRAMING)
            addToBackStack(TAG_FRAMING)
        }
    }

    fun updateToolbar() {
        val currentPreset = viewModel.selectedPreset.value
        val isFraming = supportFragmentManager.backStackEntryCount > 0 ||
                supportFragmentManager.findFragmentById(R.id.idPhotoFragmentContainer) is IdPhotoFramingFragment

        if (isFraming && currentPreset != null) {
            toolbar.title = getString(currentPreset.nameRes)
            toolbar.subtitle = "${currentPreset.widthMm}x${currentPreset.heightMm}mm · ${currentPreset.targetWidthPx}x${currentPreset.targetHeightPx} px · ${currentPreset.dpi} DPI"
            toolbar.setNavigationContentDescription(R.string.id_photo_change_preset)
        } else {
            toolbar.title = getString(R.string.image_toolbox_id_photo)
            toolbar.subtitle = null
            toolbar.setNavigationContentDescription(R.string.close)
        }
    }

    fun executeSavePipeline(cropRect: RectF, preset: IdPhotoPreset) {
        val uri = viewModel.sourceUri ?: return
        savingOverlay.isVisible = true

        lifecycleScope.launch {
            val resultUri = withContext(Dispatchers.IO) {
                runCatching {
                    IdPhotoGenerator.generate(
                        context = this@IdPhotoActivity,
                        sourceUri = uri,
                        cropRectNormalized = cropRect,
                        preset = preset,
                    )
                }.getOrNull()
            }

            savingOverlay.isVisible = false

            if (resultUri != null) {
                Toast.makeText(this@IdPhotoActivity, R.string.id_photo_success, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                Toast.makeText(this@IdPhotoActivity, R.string.id_photo_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
