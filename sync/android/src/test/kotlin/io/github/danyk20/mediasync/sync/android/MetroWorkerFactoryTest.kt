/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlin.reflect.KClass
import org.junit.Test

class MetroWorkerFactoryTest {
    private class FakeWorker(context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
        override fun startWork(): ListenableFuture<Result> = error("not run")
    }

    private val context = mockk<Context>(relaxed = true)
    private val params = mockk<WorkerParameters>(relaxed = true)
    private val factories: Map<KClass<out ListenableWorker>, () -> WorkerAssistedFactory> =
        mapOf(FakeWorker::class to { WorkerAssistedFactory { c, p -> FakeWorker(c, p) } })

    @Test
    fun buildsTheWorkerNamedByWorkManager() {
        MetroWorkerFactory(factories).createWorker(context, FakeWorker::class.java.name, params)
            .shouldBeInstanceOf<FakeWorker>()
    }

    @Test
    fun leavesWorkersItDoesNotKnowToWorkManager() {
        MetroWorkerFactory(factories).createWorker(context, "no.such.Worker", params).shouldBeNull()
    }
}
