/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { DurableObject } from "cloudflare:workers";
import { fail, ok, type Result } from "../http.ts";
import { NonceStore } from "../nonces.ts";
import { AnalyticsError, queryRelayUsage } from "./analytics.ts";
import { billingPeriod } from "./period.ts";
import {
    BYTES_PER_GB,
    defaultRelaySettings,
    type RelaySettings,
    relayLimitBytes,
    relayPricing,
    validateRelaySettings,
} from "./settings.ts";

/**
 * The relay usage meter and budget guard (PLAN §8.5). One instance per deployment (`METER_NAME`).
 *
 * Two sources, reconciled:
 * 1. **Authoritative**: every 15 minutes the cron trigger calls [refresh], which reads the period's egress
 *    from Cloudflare's TURN analytics and stores it as a snapshot.
 * 2. **Live**: during relayed sessions, devices report their relayed byte counters every minute ([report]).
 *    Reports are cumulative per WebRTC session, so lost or repeated reports don't skew the sum.
 *
 * Displayed usage = snapshot + live bytes received since the snapshot. Analytics lag behind, so live bytes
 * from the last [ANALYTICS_LAG_MILLIS] before a snapshot still count: the meter may briefly count a few
 * minutes twice but never misses traffic, which is the safe direction for a budget guard. Live bytes older
 * than that are dropped, so a wrong or bogus report only moves the meter until the next snapshot.
 */

export const METER_NAME = "global";

/** How far analytics may trail real traffic. */
export const ANALYTICS_LAG_MILLIS = 10 * 60 * 1000;
/** Session counters are kept this long after their last report, to tell repeats from new sessions. */
export const SESSION_RETENTION_MILLIS = 24 * 60 * 60 * 1000;
/** No single report adds more than this: about a minute at 1.3 Gbit/s, far beyond any phone's relay link. */
export const MAX_REPORT_DELTA_BYTES = 10 * BYTES_PER_GB;

export interface Snapshot {
    /** Start of the billing period the snapshot belongs to. */
    periodStart: number;
    /** When the analytics were read. */
    takenAt: number;
    totalBytes: number;
    byTag: Record<string, number>;
}

export interface MeterView {
    period: { start: string; end: string };
    /** What the app shows: authoritative + live. */
    usedBytes: number;
    authoritativeBytes: number;
    /** When Cloudflare's analytics were last read for this period ("last updated"); `null` if not yet. */
    authoritativeAt: string | null;
    liveBytes: number;
    /** The guard's threshold: the budget, plus the paid headroom when paid usage is allowed. */
    limitBytes: number;
    freeAllowanceBytes: number;
    /** `false` once [usedBytes] reached [limitBytes]: no relay credentials until the period resets. */
    relayAvailable: boolean;
    settings: RelaySettings;
    /** This pair's share (approximate, PLAN §8.5), when the view was requested for a pair. */
    pair?: { approxBytes: number };
}

export interface RefreshOutcome {
    refreshed: boolean;
    reason?: string;
}

const SETTINGS_KEY = "settings";
const SNAPSHOT_KEY = "snapshot";

export class UsageMeter extends DurableObject<Env> {
    private readonly sql: SqlStorage;
    private readonly nonces: NonceStore;

    constructor(ctx: DurableObjectState, env: Env) {
        super(ctx, env);
        this.sql = ctx.storage.sql;
        this.nonces = new NonceStore(this.sql);
        this.nonces.ensureSchema();
        this.sql.exec(`CREATE TABLE IF NOT EXISTS live_sessions (
            tag TEXT NOT NULL,
            session TEXT NOT NULL,
            reported INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (tag, session)
        )`);
        this.sql.exec(`CREATE TABLE IF NOT EXISTS live_usage (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tag TEXT NOT NULL,
            bytes INTEGER NOT NULL,
            at INTEGER NOT NULL
        )`);
    }

    async settings(): Promise<RelaySettings> {
        return (await this.ctx.storage.get<RelaySettings>(SETTINGS_KEY)) ?? defaultRelaySettings(this.env);
    }

    /** Replaces the settings; the Worker has already checked that [device] is an admin. */
    async updateSettings(
        device: string,
        nonce: string,
        input: Record<string, unknown>,
        now: number = Date.now(),
    ): Promise<Result<RelaySettings>> {
        if (!this.nonces.claim(device, nonce, now)) return fail(401, "auth_replayed", "This request was already used");
        let settings: RelaySettings;
        try {
            settings = validateRelaySettings(input, relayPricing(this.env));
        } catch (error) {
            return fail(400, "bad_request", error instanceof Error ? error.message : "Invalid settings");
        }
        await this.ctx.storage.put(SETTINGS_KEY, settings);
        return ok(settings);
    }

    /** Records a live report: [cumulativeBytes] relayed so far in WebRTC session [session] of pair [tag]. */
    async report(tag: string, session: string, cumulativeBytes: number, now: number = Date.now()): Promise<MeterView> {
        const previous = this.sql
            .exec<{ reported: number }>("SELECT reported FROM live_sessions WHERE tag = ? AND session = ?", tag, session)
            .toArray()[0]?.reported;
        const delta = Math.min(cumulativeBytes - (previous ?? 0), MAX_REPORT_DELTA_BYTES);
        if (delta > 0) this.sql.exec("INSERT INTO live_usage (tag, bytes, at) VALUES (?, ?, ?)", tag, delta, now);
        this.sql.exec(
            `INSERT INTO live_sessions (tag, session, reported, updated_at) VALUES (?, ?, ?, ?)
             ON CONFLICT (tag, session) DO UPDATE SET
                reported = MAX(reported, excluded.reported), updated_at = excluded.updated_at`,
            tag,
            session,
            cumulativeBytes,
            now,
        );
        return this.view(tag, now);
    }

    async view(tag?: string, now: number = Date.now()): Promise<MeterView> {
        const settings = await this.settings();
        const pricing = relayPricing(this.env);
        const period = billingPeriod(now, settings.periodStartDay);
        const stored = await this.ctx.storage.get<Snapshot>(SNAPSHOT_KEY);
        // A snapshot from an earlier period (or an earlier start day) says nothing about this one.
        const snapshot = stored?.periodStart === period.start ? stored : undefined;
        // Exclusive bound: live bytes received after it count.
        const liveSince = snapshot
            ? Math.max(period.start - 1, snapshot.takenAt - ANALYTICS_LAG_MILLIS)
            : period.start - 1;

        const liveBytes = this.liveBytes(liveSince);
        const authoritativeBytes = snapshot?.totalBytes ?? 0;
        const usedBytes = authoritativeBytes + liveBytes;
        const limitBytes = relayLimitBytes(settings, pricing);
        const view: MeterView = {
            period: { start: new Date(period.start).toISOString(), end: new Date(period.end).toISOString() },
            usedBytes,
            authoritativeBytes,
            authoritativeAt: snapshot ? new Date(snapshot.takenAt).toISOString() : null,
            liveBytes,
            limitBytes,
            freeAllowanceBytes: pricing.freeAllowanceGb * BYTES_PER_GB,
            relayAvailable: usedBytes < limitBytes,
            settings,
        };
        if (tag !== undefined) {
            view.pair = { approxBytes: (snapshot?.byTag[tag] ?? 0) + this.liveBytes(liveSince, tag) };
        }
        return view;
    }

    /**
     * Reads the period's relay egress from Cloudflare's analytics and stores it (cron trigger, PLAN §8.5).
     * Without the analytics secrets, the meter keeps running on live reports alone.
     */
    async refresh(now: number = Date.now()): Promise<RefreshOutcome> {
        const settings = await this.settings();
        const period = billingPeriod(now, settings.periodStartDay);
        this.prune(now, period.start);
        const accountId = this.env.CF_ACCOUNT_ID;
        const apiToken = this.env.CF_ANALYTICS_TOKEN;
        if (!accountId || !apiToken) return { refreshed: false, reason: "analytics_not_configured" };
        try {
            const usage = await queryRelayUsage({ accountId, apiToken, periodStart: period.start, now });
            const snapshot: Snapshot = { periodStart: period.start, takenAt: now, ...usage };
            await this.ctx.storage.put(SNAPSHOT_KEY, snapshot);
            // Live bytes this snapshot already covers are no longer needed.
            this.sql.exec("DELETE FROM live_usage WHERE at <= ?", now - ANALYTICS_LAG_MILLIS);
            return { refreshed: true };
        } catch (error) {
            // Keep the last snapshot; live reports keep the meter moving until the analytics answer again.
            const reason = error instanceof AnalyticsError ? error.message : "Analytics request failed";
            console.warn(`Relay usage refresh failed: ${reason}`);
            return { refreshed: false, reason };
        }
    }

    private liveBytes(since: number, tag?: string): number {
        const row =
            tag === undefined
                ? this.sql.exec<{ total: number | null }>("SELECT SUM(bytes) AS total FROM live_usage WHERE at > ?", since).one()
                : this.sql
                      .exec<{ total: number | null }>(
                          "SELECT SUM(bytes) AS total FROM live_usage WHERE at > ? AND tag = ?",
                          since,
                          tag,
                      )
                      .one();
        return row.total ?? 0;
    }

    private prune(now: number, periodStart: number): void {
        this.sql.exec("DELETE FROM live_usage WHERE at < ?", periodStart);
        this.sql.exec("DELETE FROM live_sessions WHERE updated_at < ?", now - SESSION_RETENTION_MILLIS);
        this.nonces.purge(now);
    }
}
