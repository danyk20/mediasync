/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * ICE servers for WebRTC (PLAN §8.4): Cloudflare STUN, which is free and needs no credentials, and
 * Cloudflare TURN, whose short-lived credentials the Worker mints per request so that no TURN secret
 * ever ships in the app.
 *
 * Each credential carries the pair's tag as Cloudflare's `customIdentifier`, which the TURN analytics
 * can group by: that's how the meter attributes relay usage to pairs (PLAN §8.5).
 */

export interface IceServer {
    urls: string[];
    username?: string;
    credential?: string;
}

/** Always handed out, also when the relay is paused: direct connections keep working (PLAN §8.5). */
export const STUN_SERVERS: IceServer[] = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] }];

export const TURN_API = "https://rtc.live.cloudflare.com/v1/turn/keys";

export class TurnError extends Error {}

interface GenerateResponse {
    iceServers?: unknown;
}

function asIceServer(value: unknown): IceServer | null {
    if (typeof value !== "object" || value === null) return null;
    const { urls, username, credential } = value as Record<string, unknown>;
    const list = typeof urls === "string" ? [urls] : urls;
    if (!Array.isArray(list) || !list.every((url) => typeof url === "string")) return null;
    const server: IceServer = { urls: list as string[] };
    if (typeof username === "string") server.username = username;
    if (typeof credential === "string") server.credential = credential;
    return server;
}

/**
 * Mints TURN credentials valid for [ttlSeconds] through Cloudflare's
 * `POST /v1/turn/keys/{key}/credentials/generate-ice-servers` and returns the ICE servers it lists.
 */
export async function mintTurnCredentials(options: {
    keyId: string;
    apiToken: string;
    ttlSeconds: number;
    customIdentifier: string;
    fetcher?: typeof fetch;
}): Promise<IceServer[]> {
    const fetcher = options.fetcher ?? fetch;
    const response = await fetcher(
        `${TURN_API}/${encodeURIComponent(options.keyId)}/credentials/generate-ice-servers`,
        {
            method: "POST",
            headers: { authorization: `Bearer ${options.apiToken}`, "content-type": "application/json" },
            body: JSON.stringify({ ttl: options.ttlSeconds, customIdentifier: options.customIdentifier }),
        },
    );
    if (!response.ok) throw new TurnError(`TURN credential request failed with HTTP ${response.status}`);
    const body = (await response.json()) as GenerateResponse;
    // The API returns a list; its older sibling endpoint returned a single object. Accept both shapes.
    const raw = Array.isArray(body.iceServers) ? body.iceServers : [body.iceServers];
    const servers = raw.map(asIceServer).filter((server): server is IceServer => server !== null);
    if (!servers.some((server) => server.username !== undefined && server.credential !== undefined)) {
        throw new TurnError("TURN credential response had no credentials");
    }
    return servers;
}
