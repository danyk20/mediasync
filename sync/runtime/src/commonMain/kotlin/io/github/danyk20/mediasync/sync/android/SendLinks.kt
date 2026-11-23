/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

/**
 * What a sending phone needs to reach its receiving phone (docs/PLAN.md §8). The receiving phone's side is [Connectivity]
 * on the JVM.
 */
class SendLinks(
    val lan: LanLinks,
    val discovery: ServiceDiscovery,
    val directLinks: DirectLinks = DirectLinks.None,
    val internet: InternetLinks = InternetLinks.None,
)
