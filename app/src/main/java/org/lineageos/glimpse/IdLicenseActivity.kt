/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.ui.dialogs.PrivacyPinDialog
import org.lineageos.glimpse.utils.privacy.IdLicenseRepository
import org.lineageos.glimpse.utils.privacy.LicenseType
import org.lineageos.glimpse.utils.privacy.PrivacyLockManager
import java.io.File

/**
 * ID & License Safe (证件夹).
 * Encrypts and securely displays user identification documents including Resident ID Card,
 * Driver's License, Passport, and other licenses.
 */
class IdLicenseActivity : AppCompatActivity(R.layout.activity_id_license) {

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

    // Slots
    private val idCardFrontSlot by lazy { findViewById<FrameLayout>(R.id.idCardFrontSlot) }
    private val idCardFrontImage by lazy { findViewById<ImageView>(R.id.idCardFrontImage) }
    private val idCardFrontPlaceholder by lazy { findViewById<View>(R.id.idCardFrontPlaceholder) }

    private val idCardBackSlot by lazy { findViewById<FrameLayout>(R.id.idCardBackSlot) }
    private val idCardBackImage by lazy { findViewById<ImageView>(R.id.idCardBackImage) }
    private val idCardBackPlaceholder by lazy { findViewById<View>(R.id.idCardBackPlaceholder) }

    private val driverMainSlot by lazy { findViewById<FrameLayout>(R.id.driverMainSlot) }
    private val driverMainImage by lazy { findViewById<ImageView>(R.id.driverMainImage) }
    private val driverMainPlaceholder by lazy { findViewById<View>(R.id.driverMainPlaceholder) }

    private val driverSubSlot by lazy { findViewById<FrameLayout>(R.id.driverSubSlot) }
    private val driverSubImage by lazy { findViewById<ImageView>(R.id.driverSubImage) }
    private val driverSubPlaceholder by lazy { findViewById<View>(R.id.driverSubPlaceholder) }

    private val passportSlot by lazy { findViewById<FrameLayout>(R.id.passportSlot) }
    private val passportImage by lazy { findViewById<ImageView>(R.id.passportImage) }
    private val passportPlaceholder by lazy { findViewById<View>(R.id.passportPlaceholder) }

    private val otherSlot by lazy { findViewById<FrameLayout>(R.id.otherSlot) }
    private val otherImage by lazy { findViewById<ImageView>(R.id.otherImage) }
    private val otherPlaceholder by lazy { findViewById<View>(R.id.otherPlaceholder) }

    private var pendingPickType: LicenseType = LicenseType.ID_CARD
    private var pendingPickSide: String = "front"
    private var isLaunchingPicker = false

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        isLaunchingPicker = false
        if (uri != null) {
            savePickedLicense(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        toolbar.setNavigationOnClickListener { finish() }

        val scrollView = findViewById<View>(R.id.licenseScrollView)
        scrollView?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updatePadding(bottom = navBar.bottom + 16)
                insets
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!PrivacyLockManager.isSessionUnlocked()) {
            PrivacyPinDialog(this, onCancel = { finish() }) {
                initSlots()
                loadLicenses()
            }.show(forcePrompt = true)
        } else {
            initSlots()
            loadLicenses()
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

    private fun initSlots() {
        bindSlot(idCardFrontSlot, LicenseType.ID_CARD, "front", getString(R.string.id_license_side_portrait))
        bindSlot(idCardBackSlot, LicenseType.ID_CARD, "back", getString(R.string.id_license_side_emblem))
        bindSlot(driverMainSlot, LicenseType.DRIVERS_LICENSE, "front", getString(R.string.id_license_side_main))
        bindSlot(driverSubSlot, LicenseType.DRIVERS_LICENSE, "back", getString(R.string.id_license_side_sub))
        bindSlot(passportSlot, LicenseType.PASSPORT, "front", getString(R.string.id_license_type_passport))
        bindSlot(otherSlot, LicenseType.OTHER, "front", getString(R.string.id_license_type_other))
    }

    private fun bindSlot(slot: View, type: LicenseType, side: String, title: String) {
        slot.setOnClickListener {
            val file = IdLicenseRepository.getLicenseFile(this, type, side)
            if (file != null) {
                showViewerDialog(file, type, side, title)
            } else {
                launchPick(type, side)
            }
        }
    }

    private fun launchPick(type: LicenseType, side: String) {
        pendingPickType = type
        pendingPickSide = side
        isLaunchingPicker = true
        pickPhotoLauncher.launch("image/*")
    }

    private fun savePickedLicense(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                IdLicenseRepository.saveLicense(this@IdLicenseActivity, pendingPickType, pendingPickSide, uri)
            }
            if (ok) {
                Toast.makeText(this@IdLicenseActivity, R.string.id_license_saved, Toast.LENGTH_SHORT).show()
                loadLicenses()
            } else {
                Toast.makeText(this@IdLicenseActivity, R.string.id_license_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showViewerDialog(file: File, type: LicenseType, side: String, title: String) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_license_viewer, null)
        val titleText = view.findViewById<TextView>(R.id.viewerTitle)
        val imageView = view.findViewById<ImageView>(R.id.viewerImage)
        val deleteButton = view.findViewById<Button>(R.id.viewerDeleteButton)
        val replaceButton = view.findViewById<Button>(R.id.viewerReplaceButton)
        val closeButton = view.findViewById<Button>(R.id.viewerCloseButton)

        titleText.text = title
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        imageView.setImageBitmap(bmp)

        deleteButton.setOnClickListener {
            IdLicenseRepository.deleteLicense(this, type, side)
            loadLicenses()
            dialog.dismiss()
            Toast.makeText(this, R.string.id_license_deleted, Toast.LENGTH_SHORT).show()
        }

        replaceButton.setOnClickListener {
            dialog.dismiss()
            launchPick(type, side)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun loadLicenses() {
        updateSlotView(idCardFrontImage, idCardFrontPlaceholder, LicenseType.ID_CARD, "front")
        updateSlotView(idCardBackImage, idCardBackPlaceholder, LicenseType.ID_CARD, "back")
        updateSlotView(driverMainImage, driverMainPlaceholder, LicenseType.DRIVERS_LICENSE, "front")
        updateSlotView(driverSubImage, driverSubPlaceholder, LicenseType.DRIVERS_LICENSE, "back")
        updateSlotView(passportImage, passportPlaceholder, LicenseType.PASSPORT, "front")
        updateSlotView(otherImage, otherPlaceholder, LicenseType.OTHER, "front")
    }

    private fun updateSlotView(imageView: ImageView, placeholder: View, type: LicenseType, side: String) {
        val file = IdLicenseRepository.getLicenseFile(this, type, side)
        if (file != null) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            imageView.setImageBitmap(bmp)
            imageView.isVisible = true
            placeholder.isVisible = false
        } else {
            imageView.setImageBitmap(null)
            imageView.isVisible = false
            placeholder.isVisible = true
        }
    }
}
