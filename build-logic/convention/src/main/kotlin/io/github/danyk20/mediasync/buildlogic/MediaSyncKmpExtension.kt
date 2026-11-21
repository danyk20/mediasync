/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.buildlogic

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Per-module switches of the `mediasync.kmp.library` convention. */
abstract class MediaSyncKmpExtension(private val kotlin: KotlinMultiplatformExtension) {
    /**
     * Also builds this module for iPhones and the iOS Simulator on Apple silicon. Only modules the iOS app needs, and
     * whose dependencies all have iOS builds, switch this on (docs/PLAN.md, M10).
     */
    fun iosTargets() {
        kotlin.iosArm64()
        kotlin.iosSimulatorArm64()
    }
}
