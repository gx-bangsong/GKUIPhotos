/* Bounded main-queue idle synchronization for instrumented UI tests.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.app.Instrumentation
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Unlike waitForIdleSync, this fails if the application queue never becomes idle. */
internal fun waitForBoundedUiIdle(instrumentation: Instrumentation, device: UiDevice, stage: String) {
    device.waitForIdle(1000)
    val idle = CountDownLatch(1)
    instrumentation.waitForIdle { idle.countDown() }
    check(idle.await(10, TimeUnit.SECONDS)) { "Application main queue did not become idle within 10 seconds: $stage" }
}

/** Run state access on the main thread without allowing a blocked queue to hang the test thread. */
internal fun runOnMainWithTimeout(instrumentation: Instrumentation, stage: String, action: () -> Unit) {
    val task = FutureTask<Unit> { instrumentation.runOnMainSync { action() }; Unit }
    Thread(task, "bounded-main-sync").apply { isDaemon = true; start() }
    try {
        task.get(10, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        task.cancel(true)
        throw AssertionError("Main thread did not complete within 10 seconds: $stage", e)
    } catch (e: ExecutionException) {
        throw (e.cause ?: e)
    }
}
