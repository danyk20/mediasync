/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { createExecutionContext, createScheduledController, env, runInDurableObject, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index.ts";
import { GRAPHQL_ENDPOINT, RELAY_USAGE_QUERY } from "../src/meter/analytics.ts";
import { BYTES_PER_GB } from "../src/meter/settings.ts";
import { ANALYTICS_LAG_MILLIS, MAX_REPORT_DELTA_BYTES, METER_NAME, SESSION_RETENTION_MILLIS } from "../src/meter/usage-meter.ts";
import { jsonResponse, mockFetch, resetMeter } from "./support/worker.ts";

const GB = BYTES_PER_GB;
const MINUTE = 60_000;
/** 2026-10-14 12:00 UTC: the period runs from 1 October to 1 November. */
const NOON = Date.UTC(2026, 9, 14, 12);
const SESSION = "AAAAAAAAAAAAAAAAAAAAAA";
const OTHER_SESSION = "BBBBBBBBBBBBBBBBBBBBBB";

afterEach(() => {
    vi.restoreAllMocks();
});

/** A fresh meter per test: each test gets its own object, so state never leaks between tests. */
function freshMeter() {
    return env.METER.get(env.METER.newUniqueId());
}

function analytics(totalBytes: number, byPair: Record<string, number> = {}) {
    return mockFetch(() =>
        jsonResponse({
            data: {
                viewer: {
                    accounts: [
                        {
                            total: [{ sum: { egressBytes: totalBytes } }],
                            byPair: Object.entries(byPair).map(([customIdentifier, egressBytes]) => ({
                                dimensions: { customIdentifier },
                                sum: { egressBytes },
                            })),
                        },
                    ],
                },
            },
            errors: null,
        }),
    );
}

describe("live reports", () => {
    it("count the growth of each session's cumulative counter", async () => {
        const meter = freshMeter();
        expect((await meter.report("pair-a", SESSION, 100, NOON)).usedBytes).toBe(100);
        expect((await meter.report("pair-a", SESSION, 250, NOON + MINUTE)).usedBytes).toBe(250);
        // Repeated and out-of-order reports add nothing.
        expect((await meter.report("pair-a", SESSION, 250, NOON + 2 * MINUTE)).usedBytes).toBe(250);
        expect((await meter.report("pair-a", SESSION, 200, NOON + 3 * MINUTE)).usedBytes).toBe(250);
        // Another session adds its own bytes.
        expect((await meter.report("pair-b", OTHER_SESSION, 50, NOON + 4 * MINUTE)).usedBytes).toBe(300);
    });

    it("attribute bytes to pairs", async () => {
        const meter = freshMeter();
        await meter.report("pair-a", SESSION, 100, NOON);
        await meter.report("pair-b", OTHER_SESSION, 40, NOON);
        expect((await meter.view("pair-a", NOON)).pair).toEqual({ approxBytes: 100 });
        expect((await meter.view("pair-b", NOON)).pair).toEqual({ approxBytes: 40 });
        expect((await meter.view(undefined, NOON)).pair).toBeUndefined();
    });

    it("can't move the meter by more than a plausible amount per report", async () => {
        const meter = freshMeter();
        const view = await meter.report("pair-a", SESSION, 500 * GB, NOON);
        expect(view.liveBytes).toBe(MAX_REPORT_DELTA_BYTES);
    });

    it("start over in a new billing period", async () => {
        const meter = freshMeter();
        await meter.report("pair-a", SESSION, 10 * GB, Date.UTC(2026, 9, 31, 23, 59));
        const view = await meter.view(undefined, Date.UTC(2026, 10, 1, 0, 1));
        expect(view.usedBytes).toBe(0);
        expect(view.period).toEqual({ start: "2026-11-01T00:00:00.000Z", end: "2026-12-01T00:00:00.000Z" });
    });
});

describe("refresh from Cloudflare's analytics", () => {
    it("runs one summed query over the period and stores the snapshot", async () => {
        const meter = freshMeter();
        const { requests } = analytics(300 * GB, { "pair-a": 200 * GB, "pair-b": 100 * GB });
        expect(await meter.refresh(NOON)).toEqual({ refreshed: true });

        expect(requests).toHaveLength(1);
        const request = requests[0]!;
        expect(request.url).toBe(GRAPHQL_ENDPOINT);
        expect(request.headers.get("authorization")).toBe("Bearer test-analytics-token");
        expect(request.json()).toEqual({
            query: RELAY_USAGE_QUERY,
            variables: { accountTag: "test-account-id", from: "2026-10-01", to: "2026-10-14" },
        });

        const view = await meter.view("pair-a", NOON + MINUTE);
        expect(view.authoritativeBytes).toBe(300 * GB);
        expect(view.authoritativeAt).toBe(new Date(NOON).toISOString());
        expect(view.usedBytes).toBe(300 * GB);
        expect(view.pair).toEqual({ approxBytes: 200 * GB });
    });

    it("adds live bytes since the snapshot, counting the analytics' lag window again to be safe", async () => {
        const meter = freshMeter();
        await meter.report("pair-a", SESSION, 5 * GB, NOON - ANALYTICS_LAG_MILLIS - MINUTE); // covered by analytics
        await meter.report("pair-a", SESSION, 7 * GB, NOON - MINUTE); // maybe not yet in analytics
        analytics(100 * GB);
        await meter.refresh(NOON);
        await meter.report("pair-a", SESSION, 10 * GB, NOON + MINUTE); // after the snapshot

        const view = await meter.view(undefined, NOON + 2 * MINUTE);
        expect(view.authoritativeBytes).toBe(100 * GB);
        expect(view.liveBytes).toBe(5 * GB);
        expect(view.usedBytes).toBe(105 * GB);
    });

    it("keeps the last snapshot when the analytics fail", async () => {
        const meter = freshMeter();
        analytics(100 * GB);
        await meter.refresh(NOON);
        vi.restoreAllMocks();

        mockFetch(() => jsonResponse({ data: null, errors: [{ message: "not authorized" }] }));
        const outcome = await meter.refresh(NOON + 15 * MINUTE);
        expect(outcome.refreshed).toBe(false);
        expect(outcome.reason).toContain("not authorized");
        vi.restoreAllMocks();

        mockFetch(() => new Response("bad gateway", { status: 502 }));
        expect((await meter.refresh(NOON + 30 * MINUTE)).refreshed).toBe(false);
        vi.restoreAllMocks();

        mockFetch(() => jsonResponse({ data: { viewer: { accounts: [] } } }));
        expect((await meter.refresh(NOON + 45 * MINUTE)).refreshed).toBe(false);

        expect((await meter.view(undefined, NOON + 46 * MINUTE)).authoritativeBytes).toBe(100 * GB);
    });

    it("treats an empty result as no usage yet", async () => {
        const meter = freshMeter();
        mockFetch(() => jsonResponse({ data: { viewer: { accounts: [{ total: [], byPair: [] }] } } }));
        expect(await meter.refresh(NOON)).toEqual({ refreshed: true });
        expect((await meter.view(undefined, NOON)).authoritativeBytes).toBe(0);
    });

    it("ignores a snapshot from the previous period", async () => {
        const meter = freshMeter();
        analytics(900 * GB);
        await meter.refresh(Date.UTC(2026, 9, 31, 23, 50));
        const view = await meter.view(undefined, Date.UTC(2026, 10, 1, 0, 5));
        expect(view.authoritativeBytes).toBe(0);
        expect(view.authoritativeAt).toBeNull();
        expect(view.relayAvailable).toBe(true);
    });

    it("prunes session counters nobody reported to for a day", async () => {
        const meter = freshMeter();
        await meter.report("pair-a", SESSION, GB, NOON);
        mockFetch(() => jsonResponse({ data: { viewer: { accounts: [{ total: [], byPair: [] }] } } }));
        await meter.refresh(NOON + SESSION_RETENTION_MILLIS + MINUTE);
        const sessions = await runInDurableObject(meter, (_, state) =>
            state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM live_sessions").one().n,
        );
        expect(sessions).toBe(0);
    });

    it("runs from the cron trigger every 15 minutes", async () => {
        const { requests } = analytics(42 * GB);
        const controller = createScheduledController({ scheduledTime: NOON, cron: "*/15 * * * *" });
        const ctx = createExecutionContext();
        await worker.scheduled(controller, env, ctx);
        await waitOnExecutionContext(ctx);
        expect(requests).toHaveLength(1);
        const view = await env.METER.get(env.METER.idFromName(METER_NAME)).view(undefined, NOON + MINUTE);
        expect(view.authoritativeBytes).toBe(42 * GB);
        // Leave the shared meter clean for other test files.
        await resetMeter();
    });

    it("keeps working on live reports alone without analytics credentials", async () => {
        const id = env.METER.newUniqueId();
        const { requests } = analytics(1);
        const outcome = await runInDurableObject(env.METER.get(id), async (instance) => {
            // Simulate a deployment without the analytics secrets.
            Object.assign((instance as unknown as { env: Env }).env, { CF_ANALYTICS_TOKEN: "" });
            try {
                return await instance.refresh(NOON);
            } finally {
                Object.assign((instance as unknown as { env: Env }).env, { CF_ANALYTICS_TOKEN: "test-analytics-token" });
            }
        });
        expect(outcome).toEqual({ refreshed: false, reason: "analytics_not_configured" });
        expect(requests).toHaveLength(0);
    });
});

describe("budget guard", () => {
    it("pauses the relay at the 950 GB default budget", async () => {
        const meter = freshMeter();
        analytics(949 * GB);
        await meter.refresh(NOON);
        let view = await meter.view(undefined, NOON);
        expect(view.limitBytes).toBe(950 * GB);
        expect(view.freeAllowanceBytes).toBe(1000 * GB);
        expect(view.relayAvailable).toBe(true);

        await meter.report("pair-a", SESSION, GB, NOON + MINUTE);
        view = await meter.view(undefined, NOON + MINUTE);
        expect(view.usedBytes).toBe(950 * GB);
        expect(view.relayAvailable).toBe(false);
    });

    it("follows the configured budget and billing period", async () => {
        const meter = freshMeter();
        const settings = { budgetGb: 500, periodStartDay: 15, allowPaid: false, paidCapUsd: 0 };
        expect(await meter.updateSettings("admin", "nonce-1", settings, NOON)).toEqual({ ok: true, value: settings });
        const view = await meter.view(undefined, NOON);
        expect(view.limitBytes).toBe(500 * GB);
        expect(view.period).toEqual({ start: "2026-09-15T00:00:00.000Z", end: "2026-10-15T00:00:00.000Z" });
        expect(view.settings).toEqual(settings);
    });

    it("adds the paid cap's worth of relay when paid usage is allowed", async () => {
        const meter = freshMeter();
        await meter.updateSettings("admin", "nonce-1", { budgetGb: 950, periodStartDay: 1, allowPaid: true, paidCapUsd: 10 }, NOON);
        // $10 at $0.05/GB = 200 GB beyond the budget.
        expect((await meter.view(undefined, NOON)).limitBytes).toBe(1150 * GB);
    });

    it("refuses invalid settings and replayed changes", async () => {
        const meter = freshMeter();
        const valid = { budgetGb: 950, periodStartDay: 1, allowPaid: false, paidCapUsd: 0 };
        expect(await meter.updateSettings("admin", "n1", { ...valid, budgetGb: 1200 }, NOON)).toMatchObject({ ok: false, status: 400 });
        expect(await meter.updateSettings("admin", "n2", { ...valid, allowPaid: true }, NOON)).toMatchObject({ ok: false, status: 400 });
        expect(await meter.updateSettings("admin", "n3", valid, NOON)).toMatchObject({ ok: true });
        expect(await meter.updateSettings("admin", "n3", valid, NOON)).toMatchObject({ ok: false, code: "auth_replayed" });
    });
});
