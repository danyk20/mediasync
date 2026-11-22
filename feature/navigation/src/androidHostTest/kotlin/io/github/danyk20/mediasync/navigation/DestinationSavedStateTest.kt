/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlinx.serialization.serializer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the saved back stack: every concrete [Destination] must be registered in [NavigationConfiguration], or it
 * cannot be saved and the app would crash on process death. Adding a destination without registering it fails here.
 */
@RunWith(RobolectricTestRunner::class)
class DestinationSavedStateTest {

    @Test
    fun everyDestinationRoundTripsThroughTheSavedBackStack() {
        val concrete = concreteSubclasses(Destination::class)
        concrete.isNotEmpty() shouldBe true

        val samples = concrete.map(::sampleOf) + listOf(
            Destination.ProducerPairing("mediasync://pair?v=1&id=00&t=AA&a=192.0.2.1:9999&n=Test"),
            Destination.ProducerWaiting(leftOut = true),
        )
        val serializer = serializer<NavKey>()
        for (sample in samples) {
            val saved = encodeToSavedState(serializer, sample, NavigationConfiguration)
            decodeFromSavedState(serializer, saved, NavigationConfiguration) shouldBe sample
        }
    }

    /** Every concrete subclass of [kClass], looking through nested sealed hierarchies. */
    private fun concreteSubclasses(kClass: KClass<*>): List<KClass<*>> =
        kClass.sealedSubclasses.flatMap { if (it.sealedSubclasses.isNotEmpty()) concreteSubclasses(it) else listOf(it) }

    /** An instance of [kClass]: the object itself, or a constructor call that uses every default. */
    private fun sampleOf(kClass: KClass<*>): Destination {
        kClass.objectInstance?.let { return it as Destination }
        val constructor =
            requireNotNull(kClass.primaryConstructor) { "${kClass.simpleName} has no primary constructor" }
        return constructor.callBy(emptyMap()) as Destination
    }
}
