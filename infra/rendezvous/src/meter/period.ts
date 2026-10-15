/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The relay billing period (PLAN §8.5): a calendar month starting at 00:00 UTC on a configurable day,
 * so it can match the Cloudflare account's billing date. Days are limited to 1–28 so that every month
 * has the start day.
 */
export interface BillingPeriod {
    /** Inclusive, epoch milliseconds. */
    start: number;
    /** Exclusive, epoch milliseconds: when the allowance resets. */
    end: number;
}

export function billingPeriod(nowMillis: number, startDay: number): BillingPeriod {
    const now = new Date(nowMillis);
    const year = now.getUTCFullYear();
    let month = now.getUTCMonth();
    if (now.getUTCDate() < startDay) month -= 1; // Date.UTC normalises month -1 to December of the year before.
    const start = Date.UTC(year, month, startDay);
    const end = Date.UTC(year, month + 1, startDay);
    return { start, end };
}

/** `YYYY-MM-DD` in UTC, the form the analytics `date` filters take. */
export function utcDate(millis: number): string {
    return new Date(millis).toISOString().slice(0, 10);
}
