/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { badRequest } from "../http.ts";

/**
 * Relay budget settings (PLAN §8.5).
 *
 * Defaults come from the Worker's vars (wrangler.toml); an admin device can override them at runtime, and
 * the override is kept by the usage meter. Byte amounts use decimal gigabytes (10^9 bytes), which can only
 * make the guard stricter if Cloudflare counts in GiB.
 */

export const BYTES_PER_GB = 1_000_000_000;

export interface RelaySettings {
    /** Stop issuing relay credentials once this many GB were relayed in the current period. */
    budgetGb: number;
    /** Day of the month (1–28, UTC midnight) the billing period starts, to match Cloudflare's billing date. */
    periodStartDay: number;
    /** Allow relay usage beyond the free allowance, up to [paidCapUsd] per period. Off by default. */
    allowPaid: boolean;
    /** Monthly cost cap for paid relay usage, in US dollars. Required (> 0) when [allowPaid] is on. */
    paidCapUsd: number;
}

export interface RelayPricing {
    /** Cloudflare's free monthly allowance, shared by TURN and the SFU (1,000 GB). */
    freeAllowanceGb: number;
    /** Price per GB beyond the allowance ($0.05). */
    priceUsdPerGb: number;
}

export interface RelayEnv {
    RELAY_BUDGET_GB: string;
    RELAY_FREE_ALLOWANCE_GB: string;
    RELAY_PRICE_USD_PER_GB: string;
    RELAY_PERIOD_START_DAY: string;
    RELAY_ALLOW_PAID: string;
    RELAY_PAID_CAP_USD: string;
}

function number(text: string | undefined, fallback: number): number {
    const value = Number(text);
    return text === undefined || text.trim() === "" || !Number.isFinite(value) ? fallback : value;
}

export function relayPricing(env: RelayEnv): RelayPricing {
    return {
        freeAllowanceGb: number(env.RELAY_FREE_ALLOWANCE_GB, 1000),
        priceUsdPerGb: number(env.RELAY_PRICE_USD_PER_GB, 0.05),
    };
}

/** The deploy-time defaults. A malformed var falls back to PLAN §8.5's default rather than failing every request. */
export function defaultRelaySettings(env: RelayEnv): RelaySettings {
    const pricing = relayPricing(env);
    const fallback: RelaySettings = { budgetGb: 950, periodStartDay: 1, allowPaid: false, paidCapUsd: 0 };
    const configured = {
        budgetGb: number(env.RELAY_BUDGET_GB, fallback.budgetGb),
        periodStartDay: number(env.RELAY_PERIOD_START_DAY, fallback.periodStartDay),
        allowPaid: env.RELAY_ALLOW_PAID === "true",
        paidCapUsd: number(env.RELAY_PAID_CAP_USD, fallback.paidCapUsd),
    };
    try {
        return validateRelaySettings(configured, pricing);
    } catch {
        console.warn("Relay settings in the Worker vars are invalid; using the defaults from PLAN §8.5");
        return fallback;
    }
}

/** Checks settings sent by an admin device (or read from vars). Throws a 400 [ApiError] when invalid. */
export function validateRelaySettings(input: Record<string, unknown>, pricing: RelayPricing): RelaySettings {
    const { budgetGb, periodStartDay, allowPaid, paidCapUsd } = input;
    if (typeof budgetGb !== "number" || !Number.isFinite(budgetGb) || budgetGb <= 0) {
        throw badRequest('"budgetGb" must be a positive number');
    }
    if (budgetGb > pricing.freeAllowanceGb) {
        throw badRequest(
            `"budgetGb" can't exceed the free allowance (${pricing.freeAllowanceGb} GB); allow paid usage instead`,
        );
    }
    if (typeof periodStartDay !== "number" || !Number.isInteger(periodStartDay) || periodStartDay < 1 || periodStartDay > 28) {
        throw badRequest('"periodStartDay" must be an integer from 1 to 28');
    }
    if (typeof allowPaid !== "boolean") throw badRequest('"allowPaid" must be a boolean');
    if (typeof paidCapUsd !== "number" || !Number.isFinite(paidCapUsd) || paidCapUsd < 0) {
        throw badRequest('"paidCapUsd" must be a non-negative number');
    }
    // PLAN §8.5: turning paid usage on requires a monthly cost cap.
    if (allowPaid && paidCapUsd <= 0) throw badRequest('Paid relay usage needs a "paidCapUsd" above 0');
    return { budgetGb, periodStartDay, allowPaid, paidCapUsd };
}

/**
 * Relayed bytes at which the guard stops issuing credentials.
 *
 * With paid usage off, that's the budget. With it on, the cap's worth of paid gigabytes is added on top of
 * the budget, so the margin below the free allowance still absorbs analytics delay and the bill stays under
 * the cap.
 */
export function relayLimitBytes(settings: RelaySettings, pricing: RelayPricing): number {
    const paidGb = settings.allowPaid ? settings.paidCapUsd / pricing.priceUsdPerGb : 0;
    return Math.floor((settings.budgetGb + paidGb) * BYTES_PER_GB);
}
