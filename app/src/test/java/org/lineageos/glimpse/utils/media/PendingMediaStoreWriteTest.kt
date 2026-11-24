/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Purpose: pins that a failed or cancelled MediaStore write deletes its pending row (in a
 * NonCancellable context) and rethrows the original exception, with a failing delete kept as a
 * suppressed exception.
 */
class PendingMediaStoreWriteTest {

    /** Records the calls made by PendingMediaStoreWrite.runSequence on a fake row. */
    private class Recorder(
        val failInsert: Throwable? = null,
        val failWrite: Throwable? = null,
        val failPublish: Throwable? = null,
        val failDelete: Throwable? = null,
        val onWrite: () -> Unit = {},
        val suspendInDelete: Boolean = false,
    ) {
        val calls = mutableListOf<String>()

        suspend fun run(): String = PendingMediaStoreWrite.runSequence(
            insert = { calls += "insert"; failInsert?.let { throw it }; "row" },
            write = { calls += "write:$it"; onWrite(); failWrite?.let { e -> throw e } },
            publish = { calls += "publish:$it"; failPublish?.let { e -> throw e } },
            delete = {
                // A suspension here throws at once in a cancelled coroutine unless it is NonCancellable.
                if (suspendInDelete) delay(10)
                calls += "delete:$it"
                failDelete?.let { e -> throw e }
            },
        )
    }

    /** Runs [block] in a coroutine and returns what it threw (null if it completed). */
    private fun thrownBy(block: suspend () -> Unit): Throwable? {
        var thrown: Throwable? = null
        runBlocking {
            launch {
                try {
                    block()
                } catch (t: Throwable) {
                    thrown = t
                }
            }.join()
        }
        return thrown
    }

    @Test
    fun successPublishesAndKeepsTheRow() {
        val r = Recorder()
        assertEquals("row", runBlocking { r.run() })
        assertEquals(listOf("insert", "write:row", "publish:row"), r.calls)
    }

    @Test
    fun writeFailureDeletesTheRowAndRethrowsTheOriginal() {
        val original = IOException("disk full")
        val r = Recorder(failWrite = original)
        val thrown = assertThrows(IOException::class.java) { runBlocking { r.run() } }
        assertSame(original, thrown)
        assertEquals(0, thrown.suppressed.size)
        assertEquals(listOf("insert", "write:row", "delete:row"), r.calls)
    }

    @Test
    fun deleteFailureIsSuppressedAndDoesNotReplaceTheOriginal() {
        val original = IOException("Injected write failure")
        val cleanup = SecurityException("Injected delete failure")
        val r = Recorder(failWrite = original, failDelete = cleanup)
        val thrown = assertThrows(IOException::class.java) { runBlocking { r.run() } }
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
        assertSame(original, assertThrows(IllegalStateException::class.java) { runBlocking { r.run() } })
        assertEquals(listOf("insert", "write:row", "publish:row", "delete:row"), r.calls)
    }

    @Test
    fun cancellationDuringWriteDeletesInNonCancellableContextAndRethrows() {
        lateinit var r: Recorder
        val cause = CancellationException("left the screen")
        val thrown = thrownBy {
            val job = coroutineContext.job
            r = Recorder(onWrite = { job.cancel(cause) }, suspendInDelete = true)
            r.run()
        }
        assertSame(cause, thrown)
        assertEquals(0, thrown!!.suppressed.size)
        // The delete suspended after the cancellation and still ran; nothing was published.
        assertEquals(listOf("insert", "write:row", "delete:row"), r.calls)
    }

    @Test
    fun cancellationWithFailingDeleteKeepsTheCancellation() {
        lateinit var r: Recorder
        val cause = CancellationException("left the screen")
        val cleanup = SecurityException("Injected delete failure")
        val thrown = thrownBy {
            val job = coroutineContext.job
            r = Recorder(onWrite = { job.cancel(cause) }, failDelete = cleanup, suspendInDelete = true)
            r.run()
        }
        assertSame(cause, thrown)
        assertEquals(1, thrown!!.suppressed.size)
        assertSame(cleanup, thrown.suppressed[0])
        assertEquals(listOf("insert", "write:row", "delete:row"), r.calls)
    }

    @Test
    fun cancellationBeforeInsertCreatesNoRow() {
        val r = Recorder()
        val thrown = thrownBy {
            coroutineContext.job.cancel()
            r.run()
        }
        assertTrue(thrown is CancellationException)
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test
    fun insertFailureHasNothingToDelete() {
        val original = IllegalStateException("MediaStore insert failed")
        val r = Recorder(failInsert = original)
        assertSame(original, assertThrows(IllegalStateException::class.java) { runBlocking { r.run() } })
        assertEquals(listOf("insert"), r.calls)
    }
}
