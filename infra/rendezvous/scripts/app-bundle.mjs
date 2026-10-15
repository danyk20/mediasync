/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

// Bundles the Worker for the app, which deploys it into the user's own Cloudflare account (PLAN §8.4, bring
// your own Cloudflare): `worker.js`, the production bundle, and `worker.json`, the settings of wrangler.toml's
// production environment that a deployment through Cloudflare's API has to repeat (the app's WorkerScript).
//
// Usage: node scripts/app-bundle.mjs [outDir]  (default: the app's assets, sync/android/src/main/assets/rendezvous)

import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { unstable_readConfig } from "wrangler";

const ENV = "production";
const outDir = resolve(process.argv[2] ?? "../../sync/android/src/main/assets/rendezvous");

const config = unstable_readConfig({ config: "wrangler.toml", env: ENV });
const migrations = config.migrations ?? [];
const manifest = {
    compatibilityDate: config.compatibility_date,
    migrationTag: migrations.at(-1)?.tag,
    durableObjects: Object.fromEntries(config.durable_objects.bindings.map((it) => [it.name, it.class_name])),
    rateLimits: (config.ratelimits ?? []).map((it) => ({
        name: it.name,
        namespaceId: it.namespace_id,
        limit: it.simple.limit,
        period: it.simple.period,
    })),
    vars: config.vars ?? {},
    crons: config.triggers?.crons ?? [],
};
if (!manifest.migrationTag) throw new Error("wrangler.toml has no Durable Object migration");

const bundleDir = mkdtempSync(join(tmpdir(), "mediasync-worker-"));
try {
    execFileSync("npx", ["wrangler", "deploy", "--dry-run", "--env", ENV, "--outdir", bundleDir], { stdio: "inherit" });
    const modules = readdirSync(bundleDir).filter((name) => name.endsWith(".js"));
    if (modules.length !== 1) throw new Error(`Expected one bundled module, got ${modules.join(", ") || "none"}`);
    mkdirSync(outDir, { recursive: true });
    writeFileSync(join(outDir, "worker.js"), readFileSync(join(bundleDir, modules[0])));
    writeFileSync(join(outDir, "worker.json"), JSON.stringify(manifest, null, 2) + "\n");
} finally {
    rmSync(bundleDir, { recursive: true, force: true });
}
console.log(`Wrote worker.js and worker.json to ${outDir}`);
