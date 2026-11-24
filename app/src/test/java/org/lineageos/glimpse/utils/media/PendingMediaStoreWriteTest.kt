/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Purpose: pins that a failed or cancelled MediaStore write deletes its pending row and
 * rethrows the original exception, with a failing delete kept as a suppressed exception.
 */
class PendingMediaStoreWriteTest {

    /** Records the calls made by PendingMediaStoreWrite.runSequence on a fake row. */
    private class Recorder(
        val failInsert: Throwable? = null,
        val failWrite: Throwable? = null,
        val failPublish: Throwable? = null,
        val failDelete: Throwable? = null,
        val cancelAtCheck: Int = -1,
    ) {
        val calls = mutableListOf<String>()
        private var checks = 0

        fun run(): String = PendingMediaStoreWrite.runSequence(
            insert = { calls += "insert"; failInsert?.let { throw it }; "row" },
            write = { calls += "write:$it"; failWrite?.let { e -> throw e } },
            publish = { calls += "publish:$it"; failPublish?.let { e -> throw e } },
            delete = { calls += "delete:$it"; failDelete?.let { e -> throw e } },
            ensureActive = {
                if (checks++ == cancelAtCheck) throw CancellationException("cancelled at check $cancelAtCheck")
            },
        )
    }

    @Test
    fun successPublishesAndKeepsTheRow() {
        val r = Recorder()
        assertEquals("row", r.run())
        assertEquals(listOf("insert", "write:row", "publish:row"), r.calls)
    }

    @Test
    fun writeFailureDeletesTheRowAndRethrowsTheOriginal() {
        val original = IOException("disk full")
        val r = Recorder(failWrite = original)
        val thrown = assertThrows(IOException::class.java) { r.run() }
        assertSame(original, thrown)
        assertEquals(0, thrown.suppressed.size)
        assertEquals(listOf("insert", "write:row", "delete:row"), r.calls)
    }

    @Test
    fun deleteFailureIsSuppressedAndDoesNotReplaceTheOriginal() {
        val original = IOException("Injected write failure")
        val cleanup = SecurityException("Injected delete failure")
        val r = Recorder(failWrite = original, failDelete = cleanup)
        val thrown = assertThrows(IOException::class.java) { r.run() }
        assertSame(original, thrown)
        assertEquals("Injected write failure", thrown.message)
        assertEquals(1, thrown.suppressed.size)
        assertSame(cleanup, thrown.suppressed[0])
        assertEquals(listOf("insert", "write:row", "delete:row"), r.calls)
    }

    @Test
    fun publishFailureDeletesTheRow() {
        val original = IllegalStateException("Publishing updated 0 rows")
        val r = Recorder(failPublish = original)
        assertSame(original, assertThrows(IllegalStateException::class.java) { r.run() })
        assertEquals(listOf("insert", "write:row", "publish:row", "delete:row"), r.calls)
    }

    @Test
    fun cancellationAfterInsertDeletesTheRowAndRethrows() {
        // Check 0 runs before insert, 1 before write, 2 before publish.
        listOf(1, 2).forEach { check ->
            val r = Recorder(cancelAtCheck = check)
            val thrown = assertThrows(CancellationException::class.java) { r.run() }
            assertEquals("cancelled at check $check", thrown.message)
            assertEquals("check $check", "delete:row", r.calls.last())
            assertTrue("check $check", "publish:row" !in r.calls)
        }
    }

    @Test
    fun cancellationBeforeInsertCreatesNoRow() {
        val r = Recorder(cancelAtCheck = 0)
        assertThrows(CancellationException::class.java) { r.run() }
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test
    fun insertFailureHasNothingToDelete() {
        val original = IllegalStateException("MediaStore insert failed")
        val r = Recorder(failInsert = original)
        assertSame(original, assertThrows(IllegalStateException::class.java) { r.run() })
        assertEquals(listOf("insert"), r.calls)
    }
}
