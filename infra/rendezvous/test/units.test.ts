/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from "vitest";
import { base64UrlDecode, base64UrlEncode, fromHex, toHex } from "../src/encoding.ts";
import { ApiError } from "../src/http.ts";
import { derivePairId, isDeviceId, pairTag } from "../src/identity.ts";
import { queryRelayUsage } from "../src/meter/analytics.ts";
import { billingPeriod, utcDate } from "../src/meter/period.ts";
import {
    BYTES_PER_GB,
    defaultRelaySettings,
    type RelayEnv,
    relayLimitBytes,
    validateRelaySettings,
} from "../src/meter/settings.ts";
import { enforceLimit } from "../src/rate-limit.ts";
import { mintTurnCredentials, TurnError } from "../src/turn.ts";

const fakeFetch = (reply: () => Response) => (async () => reply()) as unknown as typeof fetch;

describe("rate limits", () => {
    it("refuses once the limiter says so", async () => {
        const limiter = { limit: async () => ({ success: false }) } as unknown as RateLimit;
        await expect(enforceLimit(limiter, "ip:1")).rejects.toMatchObject({ status: 429, code: "rate_limited" });
    });

    it("lets everything through without the binding, as on an account that doesn't offer it", async () => {
        await expect(enforceLimit(undefined, "ip:1")).resolves.toBeUndefined();
    });
});

describe("encoding", () => {
    it("round-trips base64url without padding", () => {
        for (let length = 0; length < 40; length++) {
            const data = crypto.getRandomValues(new Uint8Array(length));
            const text = base64UrlEncode(data);
            expect(text).not.toMatch(/[+/=]/);
            expect(base64UrlDecode(text)).toEqual(data);
        }
    });

    it.each(["AA==", "a+b/", "A", "AB$"])("rejects %j", (text) => {
        expect(() => base64UrlDecode(text)).toThrow();
    });

    it("rejects non-canonical base64url", () => {
        expect(() => base64UrlDecode("AB")).toThrow(); // low bits set: "AA" is the canonical form
    });

    it("round-trips lowercase hex and rejects anything else", () => {
        expect(toHex(fromHex("00ff7a"))).toBe("00ff7a");
        expect(() => fromHex("ABCD")).toThrow();
        expect(() => fromHex("abc")).toThrow();
    });
});

describe("identity", () => {
    it("derives the pair ID as specified in ADR 0009 (known answer)", async () => {
        expect(await derivePairId("11".repeat(32), "22".repeat(32))).toBe(
            "f684b5739a33d77bf1ab7936fc3e76551e03e0044387ebb9a66fc18da5fac8df",
        );
    });

    it("depends on which device is the consumer", async () => {
        expect(await derivePairId("11".repeat(32), "22".repeat(32))).not.toBe(
            await derivePairId("22".repeat(32), "11".repeat(32)),
        );
    });

    it("tags credentials with the first 128 bits of the pair ID", () => {
        expect(pairTag("f684b5739a33d77bf1ab7936fc3e76551e03e0044387ebb9a66fc18da5fac8df")).toBe(
            "f684b5739a33d77bf1ab7936fc3e7655",
        );
    });

    it("recognises device IDs", () => {
        expect(isDeviceId("a".repeat(64))).toBe(true);
        expect(isDeviceId("A".repeat(64))).toBe(false);
        expect(isDeviceId("a".repeat(63))).toBe(false);
    });
});

describe("billingPeriod", () => {
    it.each([
        ["mid-month, start day 1", Date.UTC(2026, 9, 14, 12), 1, "2026-10-01", "2026-11-01"],
        ["exactly at the start", Date.UTC(2026, 9, 1), 1, "2026-10-01", "2026-11-01"],
        ["just before the start", Date.UTC(2026, 9, 1) - 1, 1, "2026-09-01", "2026-10-01"],
        ["before the start day", Date.UTC(2026, 9, 14), 15, "2026-09-15", "2026-10-15"],
        ["on the start day", Date.UTC(2026, 9, 15, 8), 15, "2026-10-15", "2026-11-15"],
        ["across the year end", Date.UTC(2027, 0, 10), 28, "2026-12-28", "2027-01-28"],
        ["in December", Date.UTC(2026, 11, 31), 1, "2026-12-01", "2027-01-01"],
    ])("%s", (_, now, day, start, end) => {
        const period = billingPeriod(now, day);
        expect(utcDate(period.start)).toBe(start);
        expect(utcDate(period.end)).toBe(end);
    });
});

describe("relay settings", () => {
    const pricing = { freeAllowanceGb: 1000, priceUsdPerGb: 0.05 };
    const vars: RelayEnv = {
        RELAY_BUDGET_GB: "950",
        RELAY_FREE_ALLOWANCE_GB: "1000",
        RELAY_PRICE_USD_PER_GB: "0.05",
        RELAY_PERIOD_START_DAY: "1",
        RELAY_ALLOW_PAID: "false",
        RELAY_PAID_CAP_USD: "0",
    };

    it("defaults to PLAN §8.5: 950 GB, period from the 1st, paid usage off", () => {
        expect(defaultRelaySettings(vars)).toEqual({ budgetGb: 950, periodStartDay: 1, allowPaid: false, paidCapUsd: 0 });
    });

    it("reads other values from the vars", () => {
        expect(
            defaultRelaySettings({ ...vars, RELAY_BUDGET_GB: "800", RELAY_ALLOW_PAID: "true", RELAY_PAID_CAP_USD: "5" }),
        ).toEqual({ budgetGb: 800, periodStartDay: 1, allowPaid: true, paidCapUsd: 5 });
    });

    it("falls back to the defaults when the vars are invalid", () => {
        expect(defaultRelaySettings({ ...vars, RELAY_PERIOD_START_DAY: "31" }).periodStartDay).toBe(1);
        expect(defaultRelaySettings({ ...vars, RELAY_BUDGET_GB: "lots" }).budgetGb).toBe(950);
    });

    it.each([
        [{ budgetGb: 0 }],
        [{ budgetGb: 1001 }],
        [{ budgetGb: "950" }],
        [{ periodStartDay: 29 }],
        [{ periodStartDay: 1.5 }],
        [{ allowPaid: "yes" }],
        [{ paidCapUsd: -1 }],
        [{ allowPaid: true, paidCapUsd: 0 }],
    ])("rejects %j", (change) => {
        const input = { budgetGb: 950, periodStartDay: 1, allowPaid: false, paidCapUsd: 0, ...change };
        expect(() => validateRelaySettings(input, pricing)).toThrow(ApiError);
    });

    it("computes the guard's limit", () => {
        expect(relayLimitBytes({ budgetGb: 950, periodStartDay: 1, allowPaid: false, paidCapUsd: 25 }, pricing)).toBe(
            950 * BYTES_PER_GB,
        );
        expect(relayLimitBytes({ budgetGb: 950, periodStartDay: 1, allowPaid: true, paidCapUsd: 25 }, pricing)).toBe(
            1450 * BYTES_PER_GB,
        );
    });
});

describe("mintTurnCredentials", () => {
    const options = { keyId: "k", apiToken: "t", ttlSeconds: 3600, customIdentifier: "tag" };

    it("accepts the older single-object response shape", async () => {
        const fetcher = fakeFetch(
            () => new Response(JSON.stringify({ iceServers: { urls: "turn:x", username: "u", credential: "c" } })),
        );
        expect(await mintTurnCredentials({ ...options, fetcher })).toEqual([
            { urls: ["turn:x"], username: "u", credential: "c" },
        ]);
    });

    it("drops malformed entries and insists on credentials", async () => {
        const fetcher = fakeFetch(() => new Response(JSON.stringify({ iceServers: [null, { urls: [1] }, { urls: ["stun:x"] }] })));
        await expect(mintTurnCredentials({ ...options, fetcher })).rejects.toThrow(TurnError);
    });
});

describe("queryRelayUsage", () => {
    const options = { accountId: "a", apiToken: "t", periodStart: Date.UTC(2026, 9, 1), now: Date.UTC(2026, 9, 14) };

    it("sums groups per tag and ignores untagged usage in the breakdown", async () => {
        const fetcher = fakeFetch(
            () =>
                new Response(
                    JSON.stringify({
                        data: {
                            viewer: {
                                accounts: [
                                    {
                                        total: [{ sum: { egressBytes: 30 } }],
                                        byPair: [
                                            { dimensions: { customIdentifier: "p1" }, sum: { egressBytes: 10 } },
                                            { dimensions: { customIdentifier: "" }, sum: { egressBytes: 15 } },
                                            { dimensions: { customIdentifier: "p2" }, sum: { egressBytes: 5 } },
                                        ],
                                    },
                                ],
                            },
                        },
                    }),
                ),
        );
        expect(await queryRelayUsage({ ...options, fetcher })).toEqual({ totalBytes: 30, byTag: { p1: 10, p2: 5 } });
    });
});
