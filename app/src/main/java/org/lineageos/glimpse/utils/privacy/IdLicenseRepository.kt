/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.privacy

import android.content.Context
import android.net.Uri
import org.lineageos.glimpse.R
import java.io.File
import java.io.FileOutputStream

enum class LicenseType(
    val key: String,
    val titleRes: Int,
    val isDualSided: Boolean,
    val sideLabels: Pair<Int, Int>? = null,
) {
    ID_CARD(
        "id_card",
        R.string.id_license_type_id_card,
        true,
        R.string.id_license_side_portrait to R.string.id_license_side_emblem,
    ),
    DRIVERS_LICENSE(
        "drivers_license",
        R.string.id_license_type_driver,
        true,
        R.string.id_license_side_main to R.string.id_license_side_sub,
    ),
    PASSPORT(
        "passport",
        R.string.id_license_type_passport,
        false,
    ),
    VEHICLE_LICENSE(
        "vehicle_license",
        R.string.id_license_type_vehicle,
        true,
        R.string.id_license_side_main to R.string.id_license_side_sub,
    ),
    OTHER(
        "other",
        R.string.id_license_type_other,
        false,
    );
}

/**
 * Isolated storage repository for Oppo ColorOS-style ID & License Safe (证件夹).
 * Safely holds photos of identity cards, driver's licenses, passports, and other
 * credentials in internal app storage.
 */
object IdLicenseRepository {
    private const val ID_DIR = "id_licenses"

    private fun getLicenseDirectory(context: Context): File {
        val dir = File(context.filesDir, ID_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLicenseFile(context: Context, type: LicenseType, side: String = "front"): File? {
        val dir = getLicenseDirectory(context)
        val file = File(dir, "${type.key}_${side}.jpg")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun saveLicense(context: Context, type: LicenseType, side: String = "front", sourceUri: Uri): Boolean {
        val dir = getLicenseDirectory(context)
        val targetFile = File(dir, "${type.key}_${side}.jpg")
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.exists() && targetFile.length() > 0
        }.getOrDefault(false)
    }

    fun deleteLicense(context: Context, type: LicenseType, side: String = "front"): Boolean {
        val dir = getLicenseDirectory(context)
        val file = File(dir, "${type.key}_${side}.jpg")
        return if (file.exists()) file.delete() else true
    }
}
