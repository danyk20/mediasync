/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** Builds a worker from its assisted factory, with the parameters WorkManager gives it. */
fun interface WorkerAssistedFactory {
    fun create(context: Context, params: WorkerParameters): ListenableWorker
}

/** Keys a [WorkerAssistedFactory] by the worker class it builds. */
@MapKey(unwrapValue = true)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class WorkerKey(val value: KClass<out ListenableWorker>)

/** WorkManager's [WorkerFactory] over the workers the graph provides. */
@Inject
class MetroWorkerFactory(private val factories: Map<KClass<out ListenableWorker>, () -> WorkerAssistedFactory>) :
    WorkerFactory() {
    /** `null` for a worker that isn't ours: WorkManager then tries its default factory. */
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = factories.entries
        .firstOrNull { it.key.java.name == workerClassName }
        ?.value?.invoke()?.create(appContext, workerParameters)
}
