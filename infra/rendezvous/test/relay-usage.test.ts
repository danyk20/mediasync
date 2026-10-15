/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { pairTag } from "../src/identity.ts";
import type { RelaySettings } from "../src/meter/settings.ts";
import type { MeterView } from "../src/meter/usage-meter.ts";
import { TestDevice } from "./support/device.ts";
import { adminDevice, errorCode, jsonResponse, mockFetch, registeredPair, resetMeter, send } from "./support/worker.ts";

const SESSION = "AAAAAAAAAAAAAAAAAAAAAA";

afterEach(async () => {
    vi.restoreAllMocks();
    // The meter is one object per deployment; reset it so tests don't see each other's bytes.
    await resetMeter();
});

describe("/v1/pairs/{pairId}/relay-usage", () => {
    it("records live reports and returns the meter with the pair's share", async () => {
        const { consumer, producer, pairId } = await registeredPair();
        const report = await send(
            await producer.request("POST", `/v1/pairs/${pairId}/relay-usage`, { session: SESSION, relayedBytes: 1234 }),
        );
        expect(report.status).toBe(200);
        expect(await report.json()).toMatchObject({ usedBytes: 1234, liveBytes: 1234, pair: { approxBytes: 1234 } });

        const view = await send(await consumer.request("GET", `/v1/pairs/${pairId}/relay-usage`));
        expect(view.status).toBe(200);
        const body = (await view.json()) as MeterView;
        expect(body).toMatchObject({
            usedBytes: 1234,
            limitBytes: 950_000_000_000,
            relayAvailable: true,
            pair: { approxBytes: 1234 },
            settings: { budgetGb: 950, periodStartDay: 1, allowPaid: false, paidCapUsd: 0 },
        });
        expect(body.period.start).toMatch(/^\d{4}-\d{2}-01T00:00:00.000Z$/);

        // The breakdown is per pair: another pair sees the total, not this pair's bytes as its own.
        const other = await registeredPair();
        const otherView = (await (
            await send(await other.consumer.request("GET", `/v1/pairs/${other.pairId}/relay-usage`))
        ).json()) as MeterView;
        expect(otherView.usedBytes).toBe(1234);
        expect(otherView.pair).toEqual({ approxBytes: 0 });
        expect(pairTag(pairId)).not.toBe(pairTag(other.pairId));
    });

    it.each([
        [{ relayedBytes: 1 }],
        [{ session: "short", relayedBytes: 1 }],
        [{ session: SESSION }],
        [{ session: SESSION, relayedBytes: -1 }],
        [{ session: SESSION, relayedBytes: 1.5 }],
    ])("rejects the report %j", async (body) => {
        const { producer, pairId } = await registeredPair();
        const response = await send(await producer.request("POST", `/v1/pairs/${pairId}/relay-usage`, body));
        expect(response.status).toBe(400);
    });

    it("refuses devices outside the pair", async () => {
        const { pairId } = await registeredPair();
        const stranger = await TestDevice.create();
        const report = await send(
            await stranger.request("POST", `/v1/pairs/${pairId}/relay-usage`, { session: SESSION, relayedBytes: 1 }),
        );
        expect(report.status).toBe(403);
        expect((await send(await stranger.request("GET", `/v1/pairs/${pairId}/relay-usage`))).status).toBe(403);
    });
});

describe("PUT /v1/relay/settings", () => {
    const settings: RelaySettings = { budgetGb: 400, periodStartDay: 10, allowPaid: false, paidCapUsd: 0 };

    it("lets an admin device change the budget and billing period", async () => {
        const { requests } = mockFetch(() => jsonResponse({ data: { viewer: { accounts: [{ total: [], byPair: [] }] } } }));
        const admin = await adminDevice();
        const response = await send(await admin.request("PUT", "/v1/relay/settings", settings));
        expect(response.status).toBe(200);
        expect(await response.json()).toEqual(settings);

        const { consumer, pairId } = await registeredPair();
        const view = (await (await send(await consumer.request("GET", `/v1/pairs/${pairId}/relay-usage`))).json()) as MeterView;
        expect(view.settings).toEqual(settings);
        expect(view.limitBytes).toBe(400_000_000_000);
        expect(view.period.start).toMatch(/-10T00:00:00.000Z$/);
        // The meter re-read the analytics for the new period right away.
        expect(requests.length).toBeGreaterThanOrEqual(1);
    });

    it("refuses other devices", async () => {
        const device = await TestDevice.create();
        const response = await send(await device.request("PUT", "/v1/relay/settings", settings));
        expect(response.status).toBe(403);
        expect(await errorCode(response)).toBe("not_admin");
    });

    it("refuses invalid settings", async () => {
        const admin = await adminDevice();
        const response = await send(await admin.request("PUT", "/v1/relay/settings", { ...settings, allowPaid: true }));
        expect(response.status).toBe(400);
    });

    it("refuses a replayed change", async () => {
        mockFetch(() => jsonResponse({ data: { viewer: { accounts: [{ total: [], byPair: [] }] } } }));
        const admin = await adminDevice();
        const request = await admin.request("PUT", "/v1/relay/settings", settings);
        expect((await send(request.clone())).status).toBe(200);
        const replay = await send(request);
        expect(replay.status).toBe(401);
        expect(await errorCode(replay)).toBe("auth_replayed");
    });
});
