package com.shiny.inspectionmcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.ApplicationExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WritePriorityInspectionReadPlatformTest {
    @Test
    fun `cooperative read yields to write after callback captures active worker`() {
        val indicator = EmptyProgressIndicator()
        val enteredRead = CountDownLatch(1)
        val writeRan = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actionExited = AtomicBoolean(false)
        val callbackSawActiveWorker = AtomicBoolean(false)

        val future = startCooperativeRead(
            indicator = indicator,
            enteredRead = enteredRead,
            release = release,
            actionExited = actionExited,
        ) {
            callbackSawActiveWorker.set(enteredRead.count == 0L && !actionExited.get())
        }
        try {
            assertThat(enteredRead.await(5, TimeUnit.SECONDS)).isTrue()
            ApplicationManager.getApplication().invokeLater {
                WriteAction.run<RuntimeException> { writeRan.countDown() }
            }

            assertThat(writeRan.await(5, TimeUnit.SECONDS)).isTrue()
            future.future.get(5, TimeUnit.SECONDS)
            assertThat(future.failure.get()).isInstanceOf(ExactFileProofWritePreemptedException::class.java)
            assertThat(callbackSawActiveWorker).isTrue()
            assertThat(indicator.isCanceled).isTrue()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `normal read returns its action result`() {
        val indicator = EmptyProgressIndicator()

        val result = ApplicationManager.getApplication().executeOnPooledThread<String> {
            runWritePriorityInspectionRead(indicator, {}) { "completed" }
        }.get(5, TimeUnit.SECONDS)

        assertThat(result).isEqualTo("completed")
        assertThat(indicator.isCanceled).isFalse()
    }

    @Test
    fun `caller cancellation escapes without being reclassified as write preemption`() {
        val indicator = EmptyProgressIndicator()
        val failure = AtomicReference<Throwable?>()
        val future = ApplicationManager.getApplication().executeOnPooledThread<Unit> {
            try {
                runWritePriorityInspectionRead(indicator, {}) { throw ProcessCanceledException() }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        future.get(5, TimeUnit.SECONDS)
        assertThat(failure.get()).isInstanceOf(ProcessCanceledException::class.java)
    }

    @Test
    fun `callback failure still cancels cooperative read for pending write`() {
        val indicator = EmptyProgressIndicator()
        val enteredRead = CountDownLatch(1)
        val writeRan = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actionExited = AtomicBoolean(false)

        val future = startCooperativeRead(
            indicator = indicator,
            enteredRead = enteredRead,
            release = release,
            actionExited = actionExited,
        ) {
            throw IllegalStateException("diagnostic capture failed")
        }
        try {
            assertThat(enteredRead.await(5, TimeUnit.SECONDS)).isTrue()
            ApplicationManager.getApplication().invokeLater {
                WriteAction.run<RuntimeException> { writeRan.countDown() }
            }

            assertThat(writeRan.await(5, TimeUnit.SECONDS)).isTrue()
            future.future.get(5, TimeUnit.SECONDS)
            assertThat(future.failure.get()).isInstanceOf(ExactFileProofWritePreemptedException::class.java)
            assertThat(indicator.isCanceled).isTrue()
        } finally {
            release.countDown()
        }
    }

    private fun startCooperativeRead(
        indicator: EmptyProgressIndicator,
        enteredRead: CountDownLatch,
        release: CountDownLatch,
        actionExited: AtomicBoolean,
        onPreempt: () -> Unit,
    ): CooperativeRead {
        val failure = AtomicReference<Throwable?>()
        val future = ApplicationManager.getApplication().executeOnPooledThread<Unit> {
            try {
                runWritePriorityInspectionRead(indicator, onPreempt) {
                    enteredRead.countDown()
                    try {
                        while (!release.await(25, TimeUnit.MILLISECONDS)) {
                            indicator.checkCanceled()
                        }
                        "released_without_preemption"
                    } finally {
                        actionExited.set(true)
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        return CooperativeRead(future, failure)
    }

    private data class CooperativeRead(
        val future: Future<*>,
        val failure: AtomicReference<Throwable?>,
    )

    companion object {
        @JvmField
        @RegisterExtension
        val applicationExtension = ApplicationExtension()
    }
}
