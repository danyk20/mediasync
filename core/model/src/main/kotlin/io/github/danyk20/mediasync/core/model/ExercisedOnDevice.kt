/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

/**
 * Marks code that only works against real Android services (Keystore, MediaStore, network service
 * discovery, the camera). It can't run in JVM unit tests, so it's excluded from the JVM coverage gate and is
 * tested on emulators (API 29 and 36 on every PR) instead. Every use must name the test that covers it.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FILE)
@Retention(AnnotationRetention.BINARY)
annotation class ExercisedOnDevice(val coveredBy: String)
