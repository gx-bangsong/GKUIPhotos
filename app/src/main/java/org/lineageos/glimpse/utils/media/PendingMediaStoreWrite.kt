/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Purpose: writes a MediaStore item through an IS_PENDING row and never leaves that row behind.
 *
 * The row is inserted with IS_PENDING=1, filled, then published with IS_PENDING=0. If anything
 * fails before it is published (a write error, a missing output stream, a publish that updates
 * no row, or a cancellation thrown by `ensureActive`), the row is deleted and the original
 * exception is rethrown unchanged. A failing delete is attached to it as a suppressed exception.
 */
object PendingMediaStoreWrite {

    fun insert(
        resolver: ContentResolver,
        collection: Uri,
        values: ContentValues,
        ensureActive: () -> Unit = {},
        write: (OutputStream) -> Unit,
    ): Uri = runSequence(
        insert = {
            val pending = ContentValues(values).apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
            resolver.insert(collection, pending)
                ?: throw IllegalStateException("MediaStore insert failed for $collection")
        },
        write = { uri ->
            val stream = resolver.openOutputStream(uri)
                ?: throw FileNotFoundException("Cannot open output stream for $uri")
            stream.use(write)
        },
        publish = { uri ->
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            val rows = resolver.update(uri, published, null, null)
            check(rows > 0) { "Publishing $uri updated $rows rows" }
        },
        delete = { uri ->
            val rows = resolver.delete(uri, null, null)
            check(rows > 0) { "Cleanup of $uri deleted $rows rows" }
        },
        ensureActive = ensureActive,
    )

    /** The insert / write / publish / clean-up sequence, independent of Android types for testing. */
    internal fun <T> runSequence(
        insert: () -> T,
        write: (T) -> Unit,
        publish: (T) -> Unit,
        delete: (T) -> Unit,
        ensureActive: () -> Unit = {},
    ): T {
        ensureActive()
        val row = insert()
        try {
            ensureActive()
            write(row)
            ensureActive()
            publish(row)
        } catch (original: Throwable) {
            try {
                delete(row)
            } catch (cleanup: Throwable) {
                original.addSuppressed(cleanup)
            }
            throw original
        }
        return row
    }
}
