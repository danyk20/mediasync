/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { MAX_CLOCK_SKEW_SECONDS } from "./auth.ts";

/**
 * Remembers request nonces per device, so a captured signed request can't be replayed (ADR 0009).
 *
 * A nonce only needs remembering while its timestamp would still pass the clock-skew check, so entries
 * expire after twice the allowed skew. Lives in the SQLite storage of the Durable Object that handles the
 * request; claiming is synchronous, so two concurrent requests can't both claim the same nonce.
 */
export class NonceStore {
    static readonly RETENTION_MILLIS = 2 * MAX_CLOCK_SKEW_SECONDS * 1000;

    constructor(private readonly sql: SqlStorage) {}

    ensureSchema(): void {
        this.sql.exec(`CREATE TABLE IF NOT EXISTS nonces (
            device TEXT NOT NULL,
            nonce TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            PRIMARY KEY (device, nonce)
        )`);
    }

    /** Records the nonce; `false` if this device already used it. */
    claim(device: string, nonce: string, nowMillis: number): boolean {
        this.purge(nowMillis);
        const seen = this.sql.exec("SELECT 1 FROM nonces WHERE device = ? AND nonce = ?", device, nonce).toArray();
        if (seen.length > 0) return false;
        this.sql.exec(
            "INSERT INTO nonces (device, nonce, expires_at) VALUES (?, ?, ?)",
            device,
            nonce,
            nowMillis + NonceStore.RETENTION_MILLIS,
        );
        return true;
    }

    purge(nowMillis: number): void {
        this.sql.exec("DELETE FROM nonces WHERE expires_at <= ?", nowMillis);
    }

    /** When the oldest remembered nonce can be forgotten, or `null` if none is stored. */
    nextExpiry(): number | null {
        const row = this.sql.exec<{ next: number | null }>("SELECT MIN(expires_at) AS next FROM nonces").one();
        return row.next;
    }
}
