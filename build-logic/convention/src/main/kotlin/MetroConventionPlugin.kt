/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Metro, the compile-time dependency injection: contributions to the app graph and injected classes. */
class MetroConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("metro"))
    }
}
