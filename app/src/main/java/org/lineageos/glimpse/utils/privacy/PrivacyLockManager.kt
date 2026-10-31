/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.privacy

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages the Privacy PIN authentication, salted cryptographic hashing,
 * and in-memory session unlock state for the Oppo ColorOS-style Locked Album
 * and ID & License Safe.
 */
object PrivacyLockManager {
    private const val PREFS_NAME = "privacy_lock_prefs"
    private const val KEY_SALT = "privacy_pin_salt"
    private const val KEY_HASH = "privacy_pin_hash"

    @Volatile
    private var sessionUnlocked: Boolean = false

    fun isPinSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)
    }

    fun isSessionUnlocked(): Boolean = sessionUnlocked

    fun unlockSession() {
        sessionUnlocked = true
    }

    fun lockSession() {
        sessionUnlocked = false
    }

    fun setPin(context: Context, pin: String): Boolean {
        if (pin.length < 4) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(salt, pin)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()

        unlockSession()
        return true
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val expectedHashB64 = prefs.getString(KEY_HASH, null) ?: return false

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(expectedHashB64, Base64.NO_WRAP)
        val actualHash = hashPin(salt, pin)

        val matched = MessageDigest.isEqual(expectedHash, actualHash)
        if (matched) {
            unlockSession()
        }
        return matched
    }

    private fun hashPin(salt: ByteArray, pin: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
    }
}
