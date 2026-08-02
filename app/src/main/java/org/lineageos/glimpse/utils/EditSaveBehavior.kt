/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils

/**
 * Default behaviour when the user saves an edited image.
 *
 * Used by the editor save flow (module 4) and exposed as a setting.
 */
enum class EditSaveBehavior(val key: String) {
    /** Prompt the user every time between "overwrite" and "save as new". */
    ASK("ask"),

    /** Always overwrite the original file (after requesting write access). */
    ALWAYS_OVERWRITE("always_overwrite"),

    /** Always export a brand new file next to the original. */
    ALWAYS_SAVE_AS_NEW("always_save_as_new");

    companion object {
        val DEFAULT = ASK

        fun fromKey(key: String?): EditSaveBehavior =
            values().firstOrNull { it.key == key } ?: DEFAULT
    }
}
