/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.lineageos.glimpse.models.AppRule
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceAlbumAggregatorTest {

    @get:Rule
    val failureDiagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val detail = listOfNotNull(error::class.java.simpleName, error.message)
                .joinToString(": ")
                .replace(Regex("[\\r\\n]+"), " ")
                .replace("::", "")
            println("::error title=smart-album-test::${description.methodName}: $detail")
        }
    }

    @Test
    fun enabledRulesMatchMultipleAlbumsAndDisabledRulesAreIgnored() {
        val provider = FakeMediaProvider(
            rows = listOf(
                mediaRow(1, "/storage/emulated/0/Pictures/AppOne/one.jpg"),
                mediaRow(2, "/storage/emulated/0/Pictures/AppTwo/two.jpg"),
                mediaRow(3, "/storage/emulated/0/Movies/AppThree/three.mp4", video = true),
            ),
        )
        val context = resolverContext(provider)
        val rules = listOf(
            rule("one", "AppOne", enabled = true),
            rule("two", "AppTwo", enabled = false),
            rule("three", "AppThree", enabled = true),
        )

        // No EXIF fallback is needed for rows that exercise path matching.
        val albums = SourceAlbumAggregator.aggregateWithRules(
            context,
            rules,
            maxExifScans = 0,
        )

        assertEquals(setOf("one", "three"), albums.map { it.key }.toSet())
        assertFalse(albums.any { it.key == "two" })
        assertEquals(1, albums.single { it.key == "one" }.mediaCount)
        assertEquals(1, albums.single { it.key == "three" }.mediaCount)
    }

    @Test
    fun exifFallbackStopsAtConfiguredScanLimit() {
        val provider = FakeMediaProvider(
            rows = (1L..5L).map { id ->
                mediaRow(id, "/storage/emulated/0/Pictures/Unknown/$id.jpg")
            },
        )
        val rules = listOf(rule("source", "NeverMatches", enabled = true))

        val albums = SourceAlbumAggregator.aggregateWithRules(
            resolverContext(provider),
            rules,
            maxExifScans = 2,
        )

        assertTrue(albums.isEmpty())
        assertEquals(2, provider.exifOpenUris.size)
    }

    private fun resolverContext(provider: FakeMediaProvider): Context {
        ShadowContentResolver.registerProviderInternal("media", provider)
        val application = RuntimeEnvironment.getApplication()
        return object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
        }
    }

    private fun rule(key: String, keyword: String, enabled: Boolean) = AppRule(
        id = "id-$key",
        key = key,
        displayName = key,
        enabled = enabled,
        pathKeywords = listOf(keyword),
        softwareKeywords = listOf("Software-$keyword"),
        isBuiltIn = false,
        createdAt = 1L,
    )

    private fun mediaRow(id: Long, path: String, video: Boolean = false) = MediaRow(
        id = id,
        data = path,
        type = if (video) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        } else {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        },
    )

    private data class MediaRow(
        val id: Long,
        val data: String,
        val type: Int,
    )

    private inner class FakeMediaProvider(
        private val rows: List<MediaRow>,
    ) : ContentProvider() {
        val exifOpenUris = mutableListOf<Uri>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = cursorFor(rows)

        override fun getType(uri: Uri): String? = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? {
            exifOpenUris += uri
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cursorFor(rows: List<MediaRow>): Cursor {
        val columns = listOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        var position = -1
        var closed = false

        fun value(columnIndex: Int): Any? {
            if (position !in rows.indices) throw IllegalStateException("cursor is not positioned")
            return when (columns[columnIndex]) {
                MediaStore.Files.FileColumns._ID -> rows[position].id
                MediaStore.Files.FileColumns.DATA -> rows[position].data
                MediaStore.Files.FileColumns.MEDIA_TYPE -> rows[position].type
                MediaStore.Files.FileColumns.DISPLAY_NAME -> "media-${rows[position].id}"
                MediaStore.Files.FileColumns.DATE_MODIFIED -> 0L
                else -> null
            }
        }

        return Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getCount" -> rows.size
                "getPosition" -> position
                "moveToNext" -> {
                    if (position + 1 < rows.size) {
                        position++
                        true
                    } else {
                        position = rows.size
                        false
                    }
                }
                "moveToPosition" -> {
                    val target = args!![0] as Int
                    if (target in -1..rows.size) {
                        position = target
                        target in rows.indices
                    } else {
                        false
                    }
                }
                "moveToFirst" -> {
                    position = if (rows.isEmpty()) 0 else 0
                    rows.isNotEmpty()
                }
                "moveToLast" -> {
                    position = rows.lastIndex
                    rows.isNotEmpty()
                }
                "moveToPrevious" -> {
                    if (position - 1 >= 0) {
                        position--
                        true
                    } else {
                        position = -1
                        false
                    }
                }
                "isFirst" -> position == 0 && rows.isNotEmpty()
                "isLast" -> position == rows.lastIndex && rows.isNotEmpty()
                "isBeforeFirst" -> position == -1
                "isAfterLast" -> position >= rows.size
                "getColumnIndex", "getColumnIndexOrThrow" -> {
                    val index = columns.indexOf(args!![0] as String)
                    if (method.name == "getColumnIndexOrThrow" && index < 0) {
                        throw IllegalArgumentException("unknown column")
                    }
                    index
                }
                "getColumnName" -> columns[args!![0] as Int]
                "getColumnNames" -> columns.toTypedArray()
                "getColumnCount" -> columns.size
                "getString" -> value(args!![0] as Int)?.toString()
                "getShort" -> (value(args!![0] as Int) as Number).toShort()
                "getInt" -> (value(args!![0] as Int) as Number).toInt()
                "getLong" -> (value(args!![0] as Int) as Number).toLong()
                "getFloat" -> (value(args!![0] as Int) as Number).toFloat()
                "getDouble" -> (value(args!![0] as Int) as Number).toDouble()
                "isNull" -> value(args!![0] as Int) == null
                "close" -> {
                    closed = true
                    Unit
                }
                "isClosed" -> closed
                else -> defaultValue(method.returnType)
            }
        } as Cursor
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
