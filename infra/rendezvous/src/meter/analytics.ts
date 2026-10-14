/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { utcDate } from "./period.ts";

/**
 * The authoritative relay usage: Cloudflare's TURN analytics (GraphQL dataset `callsTurnUsageAdaptiveGroups`,
 * egress bytes), PLAN §8.5.
 *
 * One request carries two selections over the whole period: `total`, a single summed group without
 * dimensions (Cloudflare's recommendation, because the analytics are sampled), and `byPair`, the same sum
 * grouped by the credential tag (`customIdentifier`) for the approximate per-pair breakdown.
 *
 * The total isn't filtered by TURN key: the free allowance belongs to the whole account, so staging,
 * production and anything else on it all count.
 */

export const GRAPHQL_ENDPOINT = "https://api.cloudflare.com/client/v4/graphql";

/** The breakdown keeps the largest pairs; a family has a handful. */
const MAX_PAIRS = 1000;

export const RELAY_USAGE_QUERY = `query RelayUsage($accountTag: string!, $from: Date!, $to: Date!) {
  viewer {
    accounts(filter: { accountTag: $accountTag }) {
      total: callsTurnUsageAdaptiveGroups(limit: 1, filter: { date_geq: $from, date_leq: $to }) {
        sum { egressBytes }
      }
      byPair: callsTurnUsageAdaptiveGroups(
        limit: ${MAX_PAIRS}
        filter: { date_geq: $from, date_leq: $to }
        orderBy: [sum_egressBytes_DESC]
      ) {
        dimensions { customIdentifier }
        sum { egressBytes }
      }
    }
  }
}`;

export interface RelayUsage {
    totalBytes: number;
    /** Egress bytes per credential tag (`customIdentifier`). Approximate: the analytics are sampled. */
    byTag: Record<string, number>;
}

interface Group {
    dimensions?: { customIdentifier?: string | null };
    sum?: { egressBytes?: number | null };
}

interface GraphQlResponse {
    data?: { viewer?: { accounts?: { total?: Group[]; byPair?: Group[] }[] } } | null;
    errors?: { message?: string }[] | null;
}

export class AnalyticsError extends Error {}

/**
 * Queries relay egress from [periodStart] (00:00 UTC, so whole days line up with the period) up to and
 * including today.
 */
export async function queryRelayUsage(options: {
    accountId: string;
    apiToken: string;
    periodStart: number;
    now: number;
    fetcher?: typeof fetch;
}): Promise<RelayUsage> {
    const fetcher = options.fetcher ?? fetch;
    const response = await fetcher(GRAPHQL_ENDPOINT, {
        method: "POST",
        headers: { authorization: `Bearer ${options.apiToken}`, "content-type": "application/json" },
        body: JSON.stringify({
            query: RELAY_USAGE_QUERY,
            variables: { accountTag: options.accountId, from: utcDate(options.periodStart), to: utcDate(options.now) },
        }),
    });
    if (!response.ok) throw new AnalyticsError(`Analytics request failed with HTTP ${response.status}`);
    const body = (await response.json()) as GraphQlResponse;
    if (body.errors && body.errors.length > 0) {
        throw new AnalyticsError(`Analytics query failed: ${body.errors.map((e) => e.message ?? "?").join("; ")}`);
    }
    const account = body.data?.viewer?.accounts?.[0];
    if (account === undefined) throw new AnalyticsError("Analytics returned no account; check CF_ACCOUNT_ID and the token");

    const totalBytes = account.total?.[0]?.sum?.egressBytes ?? 0;
    const byTag: Record<string, number> = {};
    for (const group of account.byPair ?? []) {
        const tag = group.dimensions?.customIdentifier;
        if (tag) byTag[tag] = (byTag[tag] ?? 0) + (group.sum?.egressBytes ?? 0);
    }
    return { totalBytes, byTag };
}
