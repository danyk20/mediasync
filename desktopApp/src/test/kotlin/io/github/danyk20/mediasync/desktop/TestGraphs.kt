/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.createGraphFactory
import java.nio.file.Path

/**
 * Desktop graphs for tests, each shut down like the app does it ([shutdownSteps]) before JUnit deletes its folder:
 * a graph left running keeps querying a database that is about to disappear.
 */
internal class TestGraphs {
    private val graphs = mutableListOf<DesktopGraph>()

    fun create(directory: Path): DesktopGraph =
        createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true)).also { graphs += it }

    fun shutDown() {
        graphs.forEach { AppShutdown(it.shutdownSteps()).run() }
        graphs.clear()
    }
}
