/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import type { ControlFrame } from "../../src/pair-mailbox.ts";
import type { TestDevice } from "./device.ts";
import { send } from "./worker.ts";

/** A connected signaling client that queues what it receives, for step-by-step assertions. */
export class TestSocket {
    private readonly received: (string | ArrayBuffer)[] = [];
    private readonly waiters: ((message: string | ArrayBuffer) => void)[] = [];
    closed: { code: number; reason: string } | null = null;
    private closeWaiters: (() => void)[] = [];

    constructor(readonly socket: WebSocket) {
        // The runtime hands binary frames to client sockets as Blobs; convert them in arrival order.
        let order = Promise.resolve();
        socket.addEventListener("message", (event) => {
            const raw = event.data as string | ArrayBuffer | Blob;
            order = order.then(async () => {
                const data = raw instanceof Blob ? await raw.arrayBuffer() : raw;
                const waiter = this.waiters.shift();
                if (waiter) waiter(data);
                else this.received.push(data);
            });
        });
        socket.addEventListener("close", (event) => {
            this.closed = { code: event.code, reason: event.reason };
            for (const waiter of this.closeWaiters) waiter();
            this.closeWaiters = [];
        });
        socket.accept();
    }

    /** Opens the signaling mailbox for [device]; fails the test unless the upgrade succeeds. */
    static async open(device: TestDevice, pairId: string): Promise<TestSocket> {
        const response = await send(
            await device.request("GET", `/v1/pairs/${pairId}/signal`, undefined, { headers: { upgrade: "websocket" } }),
        );
        if (response.status !== 101 || !response.webSocket) {
            throw new Error(`Upgrade failed: ${response.status} ${await response.text()}`);
        }
        return new TestSocket(response.webSocket);
    }

    next(timeoutMillis = 2000): Promise<string | ArrayBuffer> {
        const queued = this.received.shift();
        if (queued !== undefined) return Promise.resolve(queued);
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error("No message arrived")), timeoutMillis);
            this.waiters.push((message) => {
                clearTimeout(timer);
                resolve(message);
            });
        });
    }

    async nextControl(): Promise<ControlFrame> {
        const message = await this.next();
        if (typeof message !== "string") throw new Error("Expected a control frame, got binary");
        return JSON.parse(message) as ControlFrame;
    }

    async nextBinary(): Promise<Uint8Array> {
        const message = await this.next();
        if (typeof message === "string") throw new Error(`Expected binary, got ${message}`);
        return new Uint8Array(message);
    }

    /** Resolves once nothing more arrives for a moment; returns what did. */
    async drain(quietMillis = 100): Promise<(string | ArrayBuffer)[]> {
        await new Promise((resolve) => setTimeout(resolve, quietMillis));
        return this.received.splice(0);
    }

    waitForClose(timeoutMillis = 2000): Promise<{ code: number; reason: string }> {
        if (this.closed) return Promise.resolve(this.closed);
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error("The socket didn't close")), timeoutMillis);
            this.closeWaiters.push(() => {
                clearTimeout(timer);
                resolve(this.closed!);
            });
        });
    }

    send(data: string | Uint8Array): void {
        this.socket.send(data);
    }

    close(): void {
        this.socket.close(1000, "done");
    }
}
