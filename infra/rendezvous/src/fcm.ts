/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { base64UrlEncode, utf8 } from "./encoding.ts";

/**
 * Sends the consumer's wake-up push through Firebase Cloud Messaging, HTTP v1 API (PLAN §10.4).
 *
 * Authentication is Google's OAuth 2.0 service-account flow: the Worker signs a JWT (RS256) with the
 * service account's private key, exchanges it for an access token, and caches the token in memory until
 * shortly before it expires. The service account JSON is the Worker secret `FCM_SERVICE_ACCOUNT`.
 *
 * The push is a high-priority *data* message, which Android lets an app use to start background work.
 * It carries no media and nothing readable about the pair beyond its mailbox ID; the producer may add an
 * opaque payload it encrypted with the pair key.
 */

export const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
export const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
/** Seconds a wake-up stays worth delivering: an old one would open a window nobody uses. */
export const WAKE_TTL_SECONDS = 60;

export interface ServiceAccount {
    projectId: string;
    clientEmail: string;
    privateKeyPem: string;
    privateKeyId?: string;
    tokenUri: string;
}

export class FcmConfigError extends Error {}

export function parseServiceAccount(json: string): ServiceAccount {
    let value: Record<string, unknown>;
    try {
        value = JSON.parse(json) as Record<string, unknown>;
    } catch {
        throw new FcmConfigError("FCM_SERVICE_ACCOUNT isn't JSON");
    }
    const { project_id, client_email, private_key, private_key_id, token_uri } = value;
    if (typeof project_id !== "string" || typeof client_email !== "string" || typeof private_key !== "string") {
        throw new FcmConfigError("FCM_SERVICE_ACCOUNT lacks project_id, client_email or private_key");
    }
    const account: ServiceAccount = {
        projectId: project_id,
        clientEmail: client_email,
        privateKeyPem: private_key,
        tokenUri: typeof token_uri === "string" ? token_uri : DEFAULT_TOKEN_URI,
    };
    if (typeof private_key_id === "string") account.privateKeyId = private_key_id;
    return account;
}

function pemToDer(pem: string): Uint8Array {
    const base64 = pem
        .replace(/-----BEGIN PRIVATE KEY-----/, "")
        .replace(/-----END PRIVATE KEY-----/, "")
        .replace(/\s+/g, "");
    const binary = atob(base64);
    const der = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) der[i] = binary.charCodeAt(i);
    return der;
}

/** The signed JWT assertion for Google's token endpoint (RFC 7523). */
export async function signAssertion(account: ServiceAccount, nowSeconds: number): Promise<string> {
    let key: CryptoKey;
    try {
        key = await crypto.subtle.importKey(
            "pkcs8",
            pemToDer(account.privateKeyPem),
            { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
            false,
            ["sign"],
        );
    } catch {
        throw new FcmConfigError("FCM_SERVICE_ACCOUNT's private_key isn't a PKCS #8 RSA key");
    }
    const header: Record<string, string> = { alg: "RS256", typ: "JWT" };
    if (account.privateKeyId) header.kid = account.privateKeyId;
    const claims = {
        iss: account.clientEmail,
        scope: FCM_SCOPE,
        aud: account.tokenUri,
        iat: nowSeconds,
        exp: nowSeconds + 3600,
    };
    const unsigned = `${base64UrlEncode(utf8(JSON.stringify(header)))}.${base64UrlEncode(utf8(JSON.stringify(claims)))}`;
    const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, utf8(unsigned)));
    return `${unsigned}.${base64UrlEncode(signature)}`;
}

interface CachedToken {
    value: string;
    expiresAtMillis: number;
}

/** Per-isolate cache: a cold isolate simply fetches a new token. Keyed by service account. */
const tokenCache = new Map<string, CachedToken>();

/** Forgets cached access tokens; for tests. */
export function clearAccessTokenCache(): void {
    tokenCache.clear();
}

async function accessToken(account: ServiceAccount, fetcher: typeof fetch, nowMillis: number): Promise<string> {
    const cached = tokenCache.get(account.clientEmail);
    if (cached && cached.expiresAtMillis > nowMillis) return cached.value;
    const assertion = await signAssertion(account, Math.floor(nowMillis / 1000));
    const response = await fetcher(account.tokenUri, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString(),
    });
    if (!response.ok) throw new Error(`Google token request failed with HTTP ${response.status}`);
    const body = (await response.json()) as { access_token?: string; expires_in?: number };
    if (typeof body.access_token !== "string") throw new Error("Google token response had no access_token");
    // Refresh a minute early so a token never expires mid-request.
    const lifetimeSeconds = typeof body.expires_in === "number" ? body.expires_in : 3600;
    tokenCache.set(account.clientEmail, {
        value: body.access_token,
        expiresAtMillis: nowMillis + (lifetimeSeconds - 60) * 1000,
    });
    return body.access_token;
}

export type WakeOutcome =
    /** FCM accepted the message. */
    | "sent"
    /** The token is no longer valid (app uninstalled or token rotated); the caller should forget it. */
    | "unregistered"
    /** FCM or Google's token service failed; the producer may try again later. */
    | "failed";

/** Sends one high-priority data message to [token]. Retries once with a new access token on HTTP 401. */
export async function sendWake(options: {
    account: ServiceAccount;
    token: string;
    data: Record<string, string>;
    collapseKey: string;
    fetcher?: typeof fetch;
    now?: number;
}): Promise<WakeOutcome> {
    const fetcher = options.fetcher ?? fetch;
    const now = options.now ?? Date.now();
    const url = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(options.account.projectId)}/messages:send`;
    const message = {
        message: {
            token: options.token,
            data: options.data,
            android: { priority: "HIGH", ttl: `${WAKE_TTL_SECONDS}s`, collapse_key: options.collapseKey },
        },
    };
    for (let attempt = 0; attempt < 2; attempt++) {
        let bearer: string;
        try {
            bearer = await accessToken(options.account, fetcher, now);
        } catch (error) {
            if (error instanceof FcmConfigError) throw error;
            return "failed";
        }
        const response = await fetcher(url, {
            method: "POST",
            headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
            body: JSON.stringify(message),
        });
        if (response.ok) return "sent";
        if (response.status === 401 && attempt === 0) {
            tokenCache.delete(options.account.clientEmail);
            continue;
        }
        // FCM answers 404 UNREGISTERED for stale tokens and 400 INVALID_ARGUMENT for malformed ones.
        if (response.status === 404 || response.status === 400) {
            const text = await response.text();
            if (text.includes("UNREGISTERED") || text.includes("registration token")) return "unregistered";
        }
        return "failed";
    }
    return "failed";
}
