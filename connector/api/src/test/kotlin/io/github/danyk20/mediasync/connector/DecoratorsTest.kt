/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.connector.testing.FakeConnector
import io.github.danyk20.mediasync.connector.testing.FakeDeviceAppConnector
import io.github.danyk20.mediasync.connector.testing.backupItem
import io.github.danyk20.mediasync.connector.testing.contentId
import io.github.danyk20.mediasync.connector.testing.descriptor
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DecoratorsTest {

    private val item = backupItem(1)
    private val logged = mutableListOf<String>()
    private val slept = mutableListOf<Long>()

    /** Pretend time: sleeping moves it on. */
    private var nanos = 0L
    private val sleep: suspend (Long) -> Unit = { millis ->
        slept += millis
        nanos += millis * NANOS_PER_MILLI
    }

    init {
        Diagnostics.install { area, message -> logged += "${area.label}: $message" }
    }

    @AfterEach
    fun tearDown() = Diagnostics.install { _, _ -> }

    private fun failing(vararg errors: ConnectorError) = FakeConnector().apply {
        val queue = ArrayDeque(errors.toList())
        answer = { queue.removeFirstOrNull()?.let(UploadResult::Failed) ?: UploadResult.Uploaded("remote", it.size) }
    }

    @Test
    fun networkTroubleIsTriedAgainWithGrowingPauses() = runTest {
        val inner = failing(ConnectorError.Transient("timeout"), ConnectorError.Transient("reset"))

        val result = RetryingConnector(inner, random = Random(1), sleep = sleep).upload(item)

        result shouldBe UploadResult.Uploaded("remote", 7)

        inner.uploads shouldHaveSize 3
        slept shouldHaveSize 2
        slept[0] shouldBeInRange 500L..1_000L
        slept[1] shouldBeInRange 1_000L..2_000L
    }

    @Test
    fun whatRetryingCantFixIsReportedAtOnce() = runTest {
        val inner = failing(ConnectorError.SignInNeeded)

        RetryingConnector(inner, sleep = sleep).upload(item) shouldBe UploadResult.Failed(ConnectorError.SignInNeeded)

        inner.uploads shouldHaveSize 1
        slept shouldBe emptyList()
    }

    @Test
    fun triesEndAfterTheirNumber() = runTest {
        val inner = failing(*Array(10) { ConnectorError.Transient("down") })

        val result = RetryingConnector(inner, RetryPolicy(attempts = 3), sleep = sleep).upload(item)

        result shouldBe UploadResult.Failed(ConnectorError.Transient("down"))
        inner.uploads shouldHaveSize 3
    }

    @Test
    fun aRateLimitWaitsAsLongAsTheServiceAsks() = runTest {
        val inner = failing(ConnectorError.RateLimited(retryAfterMillis = 5_000))

        RetryingConnector(inner, sleep = sleep).upload(item).shouldBeInstanceOf<UploadResult.Uploaded>()

        slept.single() shouldBe 5_000L
    }

    @Test
    fun aLongWaitIsLeftToTheNextRun() = runTest {
        val hour = ConnectorError.RateLimited(retryAfterMillis = 3_600_000)
        val inner = failing(hour)

        RetryingConnector(inner, sleep = sleep).upload(item) shouldBe UploadResult.Failed(hour)

        slept shouldBe emptyList()
    }

    @Test
    fun pausesStayBetweenHalfAndAllOfTheirCeiling() {
        val policy = RetryPolicy(attempts = 30, baseDelayMillis = 1_000, maxDelayMillis = 60_000)
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            (1 until 30).forEach { attempt ->
                val ceiling = minOf(1_000L shl minOf(attempt - 1, 20), 60_000L)
                policy.delayAfter(attempt, ConnectorError.Transient("x"), random)!! shouldBeInRange ceiling / 2..ceiling
            }
        }
        policy.delayAfter(30, ConnectorError.Transient("x"), Random(0)).shouldBeNull()
        shouldThrow<IllegalArgumentException> { RetryPolicy(attempts = 0) }
        shouldThrow<IllegalArgumentException> { RetryPolicy(baseDelayMillis = 10, maxDelayMillis = 5) }
    }

    @Test
    fun theRateLimitLetsABurstThroughAndThenPacesRequests() = runTest {
        val inner = FakeConnector()
        val limited = RateLimitedConnector(inner, requestsPerSecond = 2.0, nanoTime = { nanos }, sleep = sleep)

        limited.upload(item)
        limited.upload(item)
        slept shouldBe emptyList()

        limited.upload(item)
        limited.verify(emptyList())
        limited.lookup(listOf(contentId(1)))
        slept shouldBe listOf(500L, 500L, 500L)

        nanos += 10 * NANOS_PER_SECOND // A quiet while: the burst is back, never more.
        limited.upload(item)
        limited.upload(item)
        limited.upload(item)
        slept shouldHaveSize 4
        inner.uploads shouldHaveSize 6
        inner.lookups shouldBe 1
    }

    @Test
    fun theDailyAllowanceStopsRequestsUntilTheServicesMidnight() = runTest {
        val clock = MutableClock(ZonedDateTime.parse("2026-10-14T23:30-07:00[America/Los_Angeles]").toInstant())
        val inner = FakeConnector()
        val quota = QuotaTrackingConnector(inner, requestsPerDay = 2, zone = PACIFIC, clock = clock)
        val midnight = ZonedDateTime.parse("2026-10-15T00:00-07:00[America/Los_Angeles]").toInstant().toEpochMilli()

        quota.upload(item).shouldBeInstanceOf<UploadResult.Uploaded>()
        quota.remaining() shouldBe 1
        quota.lookup(listOf(contentId(1))) shouldBe mapOf(contentId(1) to "remote-${contentId(1)}")

        quota.upload(item) shouldBe UploadResult.Failed(ConnectorError.QuotaExceeded(resetsAtMillis = midnight))
        quota.verify(listOf(receipt())) shouldBe mapOf(contentId(1) to VerifyResult.UNKNOWN)
        quota.lookup(listOf(contentId(1))) shouldBe emptyMap()
        inner.uploads shouldHaveSize 1
        inner.lookups shouldBe 1

        clock.instant = clock.instant.plusSeconds(HOUR)
        quota.remaining() shouldBe 2
        quota.verify(listOf(receipt())) shouldBe mapOf(contentId(1) to VerifyResult.PRESENT)
    }

    @Test
    fun whenTheServiceSaysTheAllowanceIsUsedUpNothingMoreIsSentThatDay() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-14T12:00:00Z"))
        val inner = failing(ConnectorError.QuotaExceeded())
        val quota = QuotaTrackingConnector(inner, requestsPerDay = 10_000, zone = PACIFIC, clock = clock)

        val first = quota.upload(item)
        val second = quota.upload(item)

        val resets = ZonedDateTime.parse("2026-10-15T00:00-07:00[America/Los_Angeles]").toInstant().toEpochMilli()
        first shouldBe UploadResult.Failed(ConnectorError.QuotaExceeded(resets))
        second shouldBe first
        inner.uploads shouldHaveSize 1
        quota.remaining() shouldBe 0
    }

    @Test
    fun everyOutcomeIsCountedAndFailuresAreLoggedByKindOnly() = runTest {
        val answers = ArrayDeque(
            listOf(
                UploadResult.Uploaded("r", 100),
                UploadResult.HandedOver("content://x"),
                UploadResult.Skipped(SkipReason.TOO_LARGE),
                UploadResult.Failed(ConnectorError.RateLimited(retryAfterMillis = 30)),
                UploadResult.Failed(ConnectorError.Transient("HTTP 503")),
                UploadResult.Failed(ConnectorError.QuotaExceeded()),
                UploadResult.Failed(ConnectorError.SignInNeeded),
            ),
        )
        val scripted = FakeConnector(answer = { answers.removeFirst() })
        val instrumented = InstrumentedConnector(scripted, nanoTime = { nanos++ })

        repeat(7) { instrumented.upload(item) }

        instrumented.metrics() shouldBe ConnectorMetrics(
            uploads = 1,
            handedOver = 1,
            skipped = 1,
            failures = 4,
            bytesSent = 100,
            uploadNanos = 7,
        )
        logged shouldContainExactly listOf(
            "backup: test-cloud: upload failed: too many requests, wait 30 ms",
            "backup: test-cloud: upload failed: network or service problem (HTTP 503)",
            "backup: test-cloud: upload failed: daily allowance used up",
            "backup: test-cloud: upload failed: sign-in needed",
        )
    }

    @Test
    fun aConnectorThatThrowsFailsItsRunInsteadOfTheApp() = runTest {
        val broken = FakeConnector(answer = { error("bug") }).apply { verifyAnswer = { error("bug") } }
        val instrumented = InstrumentedConnector(broken)

        instrumented.upload(item) shouldBe UploadResult.Failed(ConnectorError.Transient("IllegalStateException"))
        instrumented.verify(listOf(receipt())) shouldBe mapOf(contentId(1) to VerifyResult.UNKNOWN)
        logged shouldBe listOf(
            "backup: test-cloud: upload threw: IllegalStateException: bug",
            "backup: test-cloud: check threw: IllegalStateException: bug",
        )

        val cancelled = InstrumentedConnector(
            FakeConnector(answer = { throw CancellationException("stop") }).apply {
                verifyAnswer = { throw CancellationException("stop") }
            },
        )
        shouldThrow<CancellationException> { cancelled.upload(item) }
        shouldThrow<CancellationException> { cancelled.verify(emptyList()) }
    }

    @Test
    fun everyConnectorGetsTheDecoratorsItsLimitsCallFor() {
        val limits = ConnectorLimits(requestsPerSecond = 1.0, requestsPerDay = 10_000)
        val plain = FakeConnector()
        val limited = FakeConnector(descriptor(limits = limits))
        val decorators = ConnectorDecorators()

        val wrapped = decorators.wrap(limited)

        layers(wrapped) shouldBe listOf(
            InstrumentedConnector::class,
            RetryingConnector::class,
            RateLimitedConnector::class,
            QuotaTrackingConnector::class,
            FakeConnector::class,
        )
        layers(decorators.wrap(plain)) shouldBe
            listOf(InstrumentedConnector::class, RetryingConnector::class, FakeConnector::class)
        wrapped.undecorated() shouldBeSameInstanceAs limited
        wrapped.deviceApp().shouldBeNull()
        wrapped.descriptor shouldBe limited.descriptor
        val app = FakeDeviceAppConnector()
        decorators.wrap(app).deviceApp() shouldBeSameInstanceAs app
    }

    @Test
    fun decoratorsPassEverythingElseThrough() = runTest {
        val inner = FakeConnector()
        val wrapped = ConnectorDecorators().wrap(inner)

        wrapped.authState() shouldBe AuthState.SignedIn
        wrapped.lookup(listOf(contentId(2))) shouldBe mapOf(contentId(2) to "remote-${contentId(2)}")
        wrapped.verify(listOf(receipt())) shouldBe mapOf(contentId(1) to VerifyResult.PRESENT)
        inner.verified shouldHaveSize 1
        wrapped.upload(item).shouldBeInstanceOf<UploadResult.Uploaded>().bytesSent shouldBeGreaterThanOrEqual 0
    }

    private fun receipt() =
        BackupReceipt(contentId(1), AccountId("test-cloud"), "remote", BackupState.BACKED_UP, backedUpAtMillis = 0)

    private fun layers(connector: BackupConnector) =
        generateSequence(connector) { (it as? DecoratedConnector)?.inner }.map { it::class }.toList()

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }

    private companion object {
        val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val HOUR = 3_600L
        const val SEEDS = 50
    }
}
