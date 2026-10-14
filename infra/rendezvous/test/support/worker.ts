/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { env, exports } from "cloudflare:workers";
import { vi } from "vitest";
import { derivePairId } from "../../src/identity.ts";
import { METER_NAME } from "../../src/meter/usage-meter.ts";
import { TestDevice } from "./device.ts";

/** Sends a request through the Worker's real `fetch` handler. */
export function send(request: Request): Promise<Response> {
    return exports.default.fetch(request);
}

export interface ErrorBody {
    error: { code: string; message: string };
}

export async function errorCode(response: Response): Promise<string> {
    return ((await response.json()) as ErrorBody).error.code;
}

export interface Pair {
    consumer: TestDevice;
    producer: TestDevice;
    pairId: string;
}

/** Two fresh devices whose pair the consumer has registered (optionally with an FCM token). */
export async function registeredPair(fcmToken?: string): Promise<Pair> {
    const consumer = await TestDevice.create();
    const producer = await TestDevice.create();
    const pairId = await derivePairId(consumer.id, producer.id);
    const body: Record<string, unknown> = { producer: producer.id };
    if (fcmToken !== undefined) body.fcmToken = fcmToken;
    const response = await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, body));
    if (response.status !== 201) throw new Error(`Registration failed: ${response.status} ${await response.text()}`);
    return { consumer, producer, pairId };
}

export function pairStub(pairId: string) {
    return env.PAIRS.get(env.PAIRS.idFromName(pairId));
}

export function meterStub() {
    return env.METER.get(env.METER.idFromName(METER_NAME));
}

export type FetchHandler = (request: Request) => Response | Promise<Response>;

/**
 * Replaces the global `fetch`, which the Worker and its Durable Objects share with the tests in this
 * runtime, and records every outbound request. Unexpected requests fail loudly instead of going out.
 */
export function mockFetch(handler: FetchHandler) {
    const requests: Request[] = [];
    const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
        const request = new Request(input as RequestInfo, init as RequestInit);
        requests.push(request.clone());
        return handler(request);
    });
    return { requests, spy };
}

export function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

export async function adminDevice(): Promise<TestDevice> {
    return TestDevice.restore(env.TEST_ADMIN_DEVICE);
}
