/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.lineageos.glimpse.R
import org.lineageos.glimpse.utils.privacy.PrivacyLockManager

/**
 * Oppo ColorOS-style Privacy PIN dialog that prompts the user to set up or verify
 * their 4-6 digit privacy PIN to access the Locked Album or ID & License Safe.
 */
class PrivacyPinDialog(
    private val context: Context,
    private val onSuccess: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var isSettingUp = !PrivacyLockManager.isPinSet(context)
    private var pendingFirstPin: String? = null

    fun show() {
        if (PrivacyLockManager.isSessionUnlocked()) {
            onSuccess()
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_privacy_pin, null)
        val titleView = view.findViewById<TextView>(R.id.pinDialogTitle)
        val subtitleView = view.findViewById<TextView>(R.id.pinDialogSubtitle)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.pinInputLayout)
        val editText = view.findViewById<TextInputEditText>(R.id.pinEditText)
        val errorText = view.findViewById<TextView>(R.id.pinErrorTextView)
        val cancelButton = view.findViewById<Button>(R.id.pinCancelButton)
        val confirmButton = view.findViewById<Button>(R.id.pinConfirmButton)

        fun updateUiState() {
            errorText.isVisible = false
            editText.text?.clear()
            if (isSettingUp) {
                if (pendingFirstPin == null) {
                    titleView.setText(R.string.privacy_pin_create_title)
                    subtitleView.setText(R.string.privacy_pin_create_subtitle)
                    confirmButton.setText(R.string.privacy_pin_next)
                } else {
                    titleView.setText(R.string.privacy_pin_confirm_title)
                    subtitleView.setText(R.string.privacy_pin_confirm_subtitle)
                    confirmButton.setText(R.string.privacy_pin_done)
                }
            } else {
                titleView.setText(R.string.privacy_pin_enter_title)
                subtitleView.setText(R.string.privacy_pin_enter_subtitle)
                confirmButton.setText(R.string.privacy_pin_unlock)
            }
        }

        fun showError(messageRes: Int) {
            errorText.setText(messageRes)
            errorText.isVisible = true
            val shake = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
            inputLayout.startAnimation(shake)
            editText.text?.clear()
        }

        fun handleConfirm() {
            val pin = editText.text?.toString()?.trim() ?: ""
            if (pin.length < 4) {
                showError(R.string.privacy_pin_too_short)
                return
            }

            if (isSettingUp) {
                if (pendingFirstPin == null) {
                    pendingFirstPin = pin
                    updateUiState()
                } else {
                    if (pin == pendingFirstPin) {
                        PrivacyLockManager.setPin(context, pin)
                        dialog?.dismiss()
                        onSuccess()
                    } else {
                        pendingFirstPin = null
                        updateUiState()
                        showError(R.string.privacy_pin_mismatch)
                    }
                }
            } else {
                if (PrivacyLockManager.verifyPin(context, pin)) {
                    dialog?.dismiss()
                    onSuccess()
                } else {
                    showError(R.string.privacy_pin_incorrect)
                }
            }
        }

        updateUiState()

        cancelButton.setOnClickListener {
            dialog?.dismiss()
        }

        confirmButton.setOnClickListener {
            handleConfirm()
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleConfirm()
                true
            } else {
                false
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog?.setOnShowListener {
            editText.postDelayed({
                editText.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }

        dialog?.show()
    }
}
