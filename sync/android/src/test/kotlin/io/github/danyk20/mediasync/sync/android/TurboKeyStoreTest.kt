/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.sync.engine.TURBO_KEY_BYTES
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Test

/** The receiving phone's *Turbo receive* key (docs/PLAN.md §8.3). */
class TurboKeyStoreTest {

    private val dir = createTempDirectory("turbo").toFile()
    private val file = File(dir, "turbo.key")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theKeyIsMadeOnceAndKept() {
        val key = TurboKeyStore(file).current()

        key.size shouldBe TURBO_KEY_BYTES
        TurboKeyStore(file).current() shouldBe key // After a restart too.
        file.length() shouldBe TURBO_KEY_BYTES.toLong()
    }

    @Test
    fun aRenewedKeyIsNewAndKept() {
        val store = TurboKeyStore(file)
        val old = store.current()

        store.renew()

        store.current() shouldNotBe old
        TurboKeyStore(file).current() shouldBe store.current() // After a restart too.
    }

    @Test
    fun aDamagedKeyIsReplaced() {
        file.writeBytes(ByteArray(5))

        val key = TurboKeyStore(file).current()

        key.size shouldBe TURBO_KEY_BYTES
        key shouldNotBe TurboKeyStore(File(dir, "other.key")).current()
    }
}
