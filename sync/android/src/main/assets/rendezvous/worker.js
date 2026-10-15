var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// src/meter/usage-meter.ts
import { DurableObject } from "cloudflare:workers";

// src/encoding.ts
var encoder = new TextEncoder();
function utf8(text) {
  return encoder.encode(text);
}
__name(utf8, "utf8");
function base64UrlEncode(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}
__name(base64UrlEncode, "base64UrlEncode");
var BASE64URL = /^[A-Za-z0-9_-]*$/;
function base64UrlDecode(text) {
  if (!BASE64URL.test(text) || text.length % 4 === 1) throw new Error("Not base64url");
  const padded = text.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - text.length % 4) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  if (base64UrlEncode(bytes) !== text) throw new Error("Non-canonical base64url");
  return bytes;
}
__name(base64UrlDecode, "base64UrlDecode");
function toHex(bytes) {
  let hex = "";
  for (const byte of bytes) hex += byte.toString(16).padStart(2, "0");
  return hex;
}
__name(toHex, "toHex");
var LOWER_HEX = /^(?:[0-9a-f]{2})*$/;
function fromHex(hex) {
  if (!LOWER_HEX.test(hex)) throw new Error("Not lowercase hex");
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}
__name(fromHex, "fromHex");
function concatBytes(...parts) {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}
__name(concatBytes, "concatBytes");
async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}
__name(sha256, "sha256");

// src/http.ts
var ApiError = class extends Error {
  constructor(status, code, message, headers = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.headers = headers;
  }
  status;
  code;
  headers;
  static {
    __name(this, "ApiError");
  }
};
function ok(value) {
  return { ok: true, value };
}
__name(ok, "ok");
function fail(status, code, message, headers) {
  return headers === void 0 ? { ok: false, status, code, message } : { ok: false, status, code, message, headers };
}
__name(fail, "fail");
function unwrap(result) {
  if (!result.ok) throw new ApiError(result.status, result.code, result.message, result.headers);
  return result.value;
}
__name(unwrap, "unwrap");
function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers }
  });
}
__name(json, "json");
function errorResponse(error) {
  return json({ error: { code: error.code, message: error.message } }, error.status, error.headers);
}
__name(errorResponse, "errorResponse");
var MAX_BODY_BYTES = 16 * 1024;
async function readBody(request, maxBytes = MAX_BODY_BYTES) {
  const declared = request.headers.get("content-length");
  if (declared !== null && Number(declared) > maxBytes) {
    throw new ApiError(413, "payload_too_large", `Request bodies are limited to ${maxBytes} bytes`);
  }
  if (request.body === null) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  for (; ; ) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > maxBytes) {
      await reader.cancel();
      throw new ApiError(413, "payload_too_large", `Request bodies are limited to ${maxBytes} bytes`);
    }
    chunks.push(value);
  }
  return concatBytes(...chunks);
}
__name(readBody, "readBody");
function parseJsonObject(body) {
  if (body.length === 0) return {};
  let value;
  try {
    value = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(body));
  } catch {
    throw new ApiError(400, "bad_request", "The body isn't valid UTF-8 JSON");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ApiError(400, "bad_request", "The body must be a JSON object");
  }
  return value;
}
__name(parseJsonObject, "parseJsonObject");
function badRequest(message) {
  return new ApiError(400, "bad_request", message);
}
__name(badRequest, "badRequest");
function optionalString(body, field, maxLength) {
  const value = body[field];
  if (value === void 0 || value === null) return void 0;
  if (typeof value !== "string" || value.length > maxLength) {
    throw badRequest(`"${field}" must be a string of at most ${maxLength} characters`);
  }
  return value;
}
__name(optionalString, "optionalString");
function requiredString(body, field, maxLength) {
  const value = optionalString(body, field, maxLength);
  if (value === void 0) throw badRequest(`"${field}" is required`);
  return value;
}
__name(requiredString, "requiredString");
function requiredCount(body, field) {
  const value = body[field];
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw badRequest(`"${field}" must be a non-negative integer`);
  }
  return value;
}
__name(requiredCount, "requiredCount");

// src/der.ts
var TAG_INTEGER = 2;
var TAG_SEQUENCE = 48;
var TAG_CONTEXT_0 = 160;
var DerError = class extends Error {
  static {
    __name(this, "DerError");
  }
};
function readElement(der, offset, limit) {
  if (offset + 2 > limit) throw new DerError("Truncated element");
  const tag = der[offset];
  if ((tag & 31) === 31) throw new DerError("High tag numbers are not supported");
  const first = der[offset + 1];
  let length;
  let contentStart;
  if (first < 128) {
    length = first;
    contentStart = offset + 2;
  } else {
    const lengthBytes = first & 127;
    if (lengthBytes === 0 || lengthBytes > 3) throw new DerError("Unsupported length encoding");
    if (offset + 2 + lengthBytes > limit) throw new DerError("Truncated length");
    length = 0;
    for (let i = 0; i < lengthBytes; i++) length = length * 256 + der[offset + 2 + i];
    if (length < 128) throw new DerError("Non-minimal length");
    contentStart = offset + 2 + lengthBytes;
  }
  const end = contentStart + length;
  if (end > limit) throw new DerError("Element longer than its container");
  return { tag, start: offset, contentStart, end };
}
__name(readElement, "readElement");
function children(der, parent) {
  const result = [];
  let offset = parent.contentStart;
  while (offset < parent.end) {
    const child = readElement(der, offset, parent.end);
    result.push(child);
    offset = child.end;
  }
  return result;
}
__name(children, "children");
function expect(element, tag, what) {
  if (element === void 0 || element.tag !== tag) throw new DerError(`Expected ${what}`);
  return element;
}
__name(expect, "expect");
function subjectPublicKeyInfo(certificate) {
  const cert = expect(readElement(certificate, 0, certificate.length), TAG_SEQUENCE, "Certificate");
  if (cert.end !== certificate.length) throw new DerError("Trailing bytes after the certificate");
  const tbs = expect(children(certificate, cert)[0], TAG_SEQUENCE, "TBSCertificate");
  const fields = children(certificate, tbs);
  const index = fields[0]?.tag === TAG_CONTEXT_0 ? 6 : 5;
  expect(fields[index - 5], TAG_INTEGER, "serialNumber");
  const spki = expect(fields[index], TAG_SEQUENCE, "SubjectPublicKeyInfo");
  return certificate.slice(spki.start, spki.end);
}
__name(subjectPublicKeyInfo, "subjectPublicKeyInfo");
function fixedWidth(der, element, size) {
  let start = element.contentStart;
  if (element.end === start) throw new DerError("Empty INTEGER");
  if (der[start] & 128) throw new DerError("Negative INTEGER");
  while (start < element.end - 1 && der[start] === 0) start++;
  const length = element.end - start;
  if (length > size) throw new DerError("INTEGER too large");
  const out = new Uint8Array(size);
  out.set(der.subarray(start, element.end), size - length);
  return out;
}
__name(fixedWidth, "fixedWidth");
function ecdsaDerToP1363(signature, size = 32) {
  const sequence = expect(readElement(signature, 0, signature.length), TAG_SEQUENCE, "ECDSA signature");
  if (sequence.end !== signature.length) throw new DerError("Trailing bytes after the signature");
  const parts = children(signature, sequence);
  if (parts.length !== 2) throw new DerError("Expected exactly r and s");
  const r = fixedWidth(signature, expect(parts[0], TAG_INTEGER, "r"), size);
  const s = fixedWidth(signature, expect(parts[1], TAG_INTEGER, "s"), size);
  const out = new Uint8Array(size * 2);
  out.set(r, 0);
  out.set(s, size);
  return out;
}
__name(ecdsaDerToP1363, "ecdsaDerToP1363");

// src/auth.ts
var AUTH_LABEL = "MEDIASYNC-AUTH-V1";
var AUTH_HEADERS = {
  certificate: "X-MediaSync-Certificate",
  timestamp: "X-MediaSync-Timestamp",
  nonce: "X-MediaSync-Nonce",
  signature: "X-MediaSync-Signature"
};
var SERVER_TIME_HEADER = "X-MediaSync-Server-Time";
var MAX_CLOCK_SKEW_SECONDS = 300;
var NONCE_BYTES = 16;
var MAX_CERTIFICATE_BYTES = 4096;
function canonicalString(request) {
  return [
    AUTH_LABEL,
    request.method.toUpperCase(),
    request.host.toLowerCase(),
    request.path,
    request.query,
    request.timestamp,
    request.nonce,
    request.bodySha256Hex
  ].join("\n");
}
__name(canonicalString, "canonicalString");
function unauthorized(code, message) {
  return new ApiError(401, code, message);
}
__name(unauthorized, "unauthorized");
function decodeHeader(request, name) {
  const value = request.headers.get(name);
  if (value === null || value === "") throw unauthorized("auth_missing", `Missing ${name}`);
  try {
    return base64UrlDecode(value);
  } catch {
    throw unauthorized("auth_malformed", `${name} isn't base64url without padding`);
  }
}
__name(decodeHeader, "decodeHeader");
async function authenticate(request, body, nowMillis) {
  const certificate = decodeHeader(request, AUTH_HEADERS.certificate);
  const signature = decodeHeader(request, AUTH_HEADERS.signature);
  const nonceText = request.headers.get(AUTH_HEADERS.nonce) ?? "";
  const nonce = decodeHeader(request, AUTH_HEADERS.nonce);
  const timestampText = request.headers.get(AUTH_HEADERS.timestamp) ?? "";
  if (nonce.length !== NONCE_BYTES) throw unauthorized("auth_malformed", `The nonce must be ${NONCE_BYTES} bytes`);
  if (certificate.length > MAX_CERTIFICATE_BYTES) throw unauthorized("auth_malformed", "Certificate too large");
  if (!/^[0-9]{1,12}$/.test(timestampText)) {
    throw unauthorized(timestampText === "" ? "auth_missing" : "auth_malformed", "Bad timestamp");
  }
  const timestamp = Number(timestampText);
  if (Math.abs(nowMillis / 1e3 - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
    throw new ApiError(401, "auth_clock_skew", `The timestamp is more than ${MAX_CLOCK_SKEW_SECONDS} s off`, {
      [SERVER_TIME_HEADER]: `${Math.floor(nowMillis / 1e3)}`
    });
  }
  let key;
  let rawSignature;
  try {
    key = await crypto.subtle.importKey(
      "spki",
      subjectPublicKeyInfo(certificate),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );
    rawSignature = ecdsaDerToP1363(signature);
  } catch (error) {
    const detail = error instanceof DerError ? error.message : "The certificate's key isn't EC P-256";
    throw unauthorized("auth_malformed", detail);
  }
  const url = new URL(request.url);
  const message = canonicalString({
    method: request.method,
    host: url.host,
    path: url.pathname,
    query: url.search.replace(/^\?/, ""),
    timestamp: timestampText,
    nonce: nonceText,
    bodySha256Hex: toHex(await sha256(body))
  });
  const valid = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, rawSignature, utf8(message));
  if (!valid) throw unauthorized("auth_bad_signature", "The signature doesn't match the request");
  return { id: toHex(await sha256(certificate)), nonce: nonceText, timestamp };
}
__name(authenticate, "authenticate");

// src/nonces.ts
var NonceStore = class _NonceStore {
  constructor(sql) {
    this.sql = sql;
  }
  sql;
  static {
    __name(this, "NonceStore");
  }
  static RETENTION_MILLIS = 2 * MAX_CLOCK_SKEW_SECONDS * 1e3;
  ensureSchema() {
    this.sql.exec(`CREATE TABLE IF NOT EXISTS nonces (
            device TEXT NOT NULL,
            nonce TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            PRIMARY KEY (device, nonce)
        )`);
  }
  /** Records the nonce; `false` if this device already used it. */
  claim(device, nonce, nowMillis) {
    this.purge(nowMillis);
    const seen = this.sql.exec("SELECT 1 FROM nonces WHERE device = ? AND nonce = ?", device, nonce).toArray();
    if (seen.length > 0) return false;
    this.sql.exec(
      "INSERT INTO nonces (device, nonce, expires_at) VALUES (?, ?, ?)",
      device,
      nonce,
      nowMillis + _NonceStore.RETENTION_MILLIS
    );
    return true;
  }
  purge(nowMillis) {
    this.sql.exec("DELETE FROM nonces WHERE expires_at <= ?", nowMillis);
  }
  /** When the oldest remembered nonce can be forgotten, or `null` if none is stored. */
  nextExpiry() {
    const row = this.sql.exec("SELECT MIN(expires_at) AS next FROM nonces").one();
    return row.next;
  }
};

// src/meter/period.ts
function billingPeriod(nowMillis, startDay) {
  const now = new Date(nowMillis);
  const year = now.getUTCFullYear();
  let month = now.getUTCMonth();
  if (now.getUTCDate() < startDay) month -= 1;
  const start = Date.UTC(year, month, startDay);
  const end = Date.UTC(year, month + 1, startDay);
  return { start, end };
}
__name(billingPeriod, "billingPeriod");
function utcDate(millis) {
  return new Date(millis).toISOString().slice(0, 10);
}
__name(utcDate, "utcDate");

// src/meter/analytics.ts
var GRAPHQL_ENDPOINT = "https://api.cloudflare.com/client/v4/graphql";
var MAX_PAIRS = 1e3;
var RELAY_USAGE_QUERY = `query RelayUsage($accountTag: string!, $from: Date!, $to: Date!) {
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
var AnalyticsError = class extends Error {
  static {
    __name(this, "AnalyticsError");
  }
};
async function queryRelayUsage(options) {
  const fetcher = options.fetcher ?? fetch;
  const response = await fetcher(GRAPHQL_ENDPOINT, {
    method: "POST",
    headers: { authorization: `Bearer ${options.apiToken}`, "content-type": "application/json" },
    body: JSON.stringify({
      query: RELAY_USAGE_QUERY,
      variables: { accountTag: options.accountId, from: utcDate(options.periodStart), to: utcDate(options.now) }
    })
  });
  if (!response.ok) throw new AnalyticsError(`Analytics request failed with HTTP ${response.status}`);
  const body = await response.json();
  if (body.errors && body.errors.length > 0) {
    throw new AnalyticsError(`Analytics query failed: ${body.errors.map((e) => e.message ?? "?").join("; ")}`);
  }
  const account = body.data?.viewer?.accounts?.[0];
  if (account === void 0) throw new AnalyticsError("Analytics returned no account; check CF_ACCOUNT_ID and the token");
  const totalBytes = account.total?.[0]?.sum?.egressBytes ?? 0;
  const byTag = {};
  for (const group of account.byPair ?? []) {
    const tag = group.dimensions?.customIdentifier;
    if (tag) byTag[tag] = (byTag[tag] ?? 0) + (group.sum?.egressBytes ?? 0);
  }
  return { totalBytes, byTag };
}
__name(queryRelayUsage, "queryRelayUsage");

// src/meter/settings.ts
var BYTES_PER_GB = 1e9;
function number(text, fallback) {
  const value = Number(text);
  return text === void 0 || text.trim() === "" || !Number.isFinite(value) ? fallback : value;
}
__name(number, "number");
function relayPricing(env) {
  return {
    freeAllowanceGb: number(env.RELAY_FREE_ALLOWANCE_GB, 1e3),
    priceUsdPerGb: number(env.RELAY_PRICE_USD_PER_GB, 0.05)
  };
}
__name(relayPricing, "relayPricing");
function defaultRelaySettings(env) {
  const pricing = relayPricing(env);
  const fallback = { budgetGb: 950, periodStartDay: 1, allowPaid: false, paidCapUsd: 0 };
  const configured = {
    budgetGb: number(env.RELAY_BUDGET_GB, fallback.budgetGb),
    periodStartDay: number(env.RELAY_PERIOD_START_DAY, fallback.periodStartDay),
    allowPaid: env.RELAY_ALLOW_PAID === "true",
    paidCapUsd: number(env.RELAY_PAID_CAP_USD, fallback.paidCapUsd)
  };
  try {
    return validateRelaySettings(configured, pricing);
  } catch {
    console.warn("Relay settings in the Worker vars are invalid; using the defaults from PLAN \xA78.5");
    return fallback;
  }
}
__name(defaultRelaySettings, "defaultRelaySettings");
function validateRelaySettings(input, pricing) {
  const { budgetGb, periodStartDay, allowPaid, paidCapUsd } = input;
  if (typeof budgetGb !== "number" || !Number.isFinite(budgetGb) || budgetGb <= 0) {
    throw badRequest('"budgetGb" must be a positive number');
  }
  if (budgetGb > pricing.freeAllowanceGb) {
    throw badRequest(
      `"budgetGb" can't exceed the free allowance (${pricing.freeAllowanceGb} GB); allow paid usage instead`
    );
  }
  if (typeof periodStartDay !== "number" || !Number.isInteger(periodStartDay) || periodStartDay < 1 || periodStartDay > 28) {
    throw badRequest('"periodStartDay" must be an integer from 1 to 28');
  }
  if (typeof allowPaid !== "boolean") throw badRequest('"allowPaid" must be a boolean');
  if (typeof paidCapUsd !== "number" || !Number.isFinite(paidCapUsd) || paidCapUsd < 0) {
    throw badRequest('"paidCapUsd" must be a non-negative number');
  }
  if (allowPaid && paidCapUsd <= 0) throw badRequest('Paid relay usage needs a "paidCapUsd" above 0');
  return { budgetGb, periodStartDay, allowPaid, paidCapUsd };
}
__name(validateRelaySettings, "validateRelaySettings");
function relayLimitBytes(settings, pricing) {
  const paidGb = settings.allowPaid ? settings.paidCapUsd / pricing.priceUsdPerGb : 0;
  return Math.floor((settings.budgetGb + paidGb) * BYTES_PER_GB);
}
__name(relayLimitBytes, "relayLimitBytes");

// src/meter/usage-meter.ts
var METER_NAME = "global";
var ANALYTICS_LAG_MILLIS = 10 * 60 * 1e3;
var SESSION_RETENTION_MILLIS = 24 * 60 * 60 * 1e3;
var MAX_REPORT_DELTA_BYTES = 10 * BYTES_PER_GB;
var SETTINGS_KEY = "settings";
var SNAPSHOT_KEY = "snapshot";
var UsageMeter = class extends DurableObject {
  static {
    __name(this, "UsageMeter");
  }
  sql;
  nonces;
  constructor(ctx, env) {
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
  async settings() {
    return await this.ctx.storage.get(SETTINGS_KEY) ?? defaultRelaySettings(this.env);
  }
  /** Replaces the settings; the Worker has already checked that [device] is an admin. */
  async updateSettings(device, nonce, input, now = Date.now()) {
    if (!this.nonces.claim(device, nonce, now)) return fail(401, "auth_replayed", "This request was already used");
    let settings;
    try {
      settings = validateRelaySettings(input, relayPricing(this.env));
    } catch (error) {
      return fail(400, "bad_request", error instanceof Error ? error.message : "Invalid settings");
    }
    await this.ctx.storage.put(SETTINGS_KEY, settings);
    return ok(settings);
  }
  /** Records a live report: [cumulativeBytes] relayed so far in WebRTC session [session] of pair [tag]. */
  async report(tag, session, cumulativeBytes, now = Date.now()) {
    const previous = this.sql.exec("SELECT reported FROM live_sessions WHERE tag = ? AND session = ?", tag, session).toArray()[0]?.reported;
    const delta = Math.min(cumulativeBytes - (previous ?? 0), MAX_REPORT_DELTA_BYTES);
    if (delta > 0) this.sql.exec("INSERT INTO live_usage (tag, bytes, at) VALUES (?, ?, ?)", tag, delta, now);
    this.sql.exec(
      `INSERT INTO live_sessions (tag, session, reported, updated_at) VALUES (?, ?, ?, ?)
             ON CONFLICT (tag, session) DO UPDATE SET
                reported = MAX(reported, excluded.reported), updated_at = excluded.updated_at`,
      tag,
      session,
      cumulativeBytes,
      now
    );
    return this.view(tag, now);
  }
  async view(tag, now = Date.now()) {
    const settings = await this.settings();
    const pricing = relayPricing(this.env);
    const period = billingPeriod(now, settings.periodStartDay);
    const stored = await this.ctx.storage.get(SNAPSHOT_KEY);
    const snapshot = stored?.periodStart === period.start ? stored : void 0;
    const liveSince = snapshot ? Math.max(period.start - 1, snapshot.takenAt - ANALYTICS_LAG_MILLIS) : period.start - 1;
    const liveBytes = this.liveBytes(liveSince);
    const authoritativeBytes = snapshot?.totalBytes ?? 0;
    const usedBytes = authoritativeBytes + liveBytes;
    const limitBytes = relayLimitBytes(settings, pricing);
    const view = {
      period: { start: new Date(period.start).toISOString(), end: new Date(period.end).toISOString() },
      usedBytes,
      authoritativeBytes,
      authoritativeAt: snapshot ? new Date(snapshot.takenAt).toISOString() : null,
      liveBytes,
      limitBytes,
      freeAllowanceBytes: pricing.freeAllowanceGb * BYTES_PER_GB,
      relayAvailable: usedBytes < limitBytes,
      settings
    };
    if (tag !== void 0) {
      view.pair = { approxBytes: (snapshot?.byTag[tag] ?? 0) + this.liveBytes(liveSince, tag) };
    }
    return view;
  }
  /**
   * Reads the period's relay egress from Cloudflare's analytics and stores it (cron trigger, PLAN §8.5).
   * Without the analytics secrets, the meter keeps running on live reports alone.
   */
  async refresh(now = Date.now()) {
    const settings = await this.settings();
    const period = billingPeriod(now, settings.periodStartDay);
    this.prune(now, period.start);
    const accountId = this.env.CF_ACCOUNT_ID;
    const apiToken = this.env.CF_ANALYTICS_TOKEN;
    if (!accountId || !apiToken) return { refreshed: false, reason: "analytics_not_configured" };
    try {
      const usage = await queryRelayUsage({ accountId, apiToken, periodStart: period.start, now });
      const snapshot = { periodStart: period.start, takenAt: now, ...usage };
      await this.ctx.storage.put(SNAPSHOT_KEY, snapshot);
      this.sql.exec("DELETE FROM live_usage WHERE at <= ?", now - ANALYTICS_LAG_MILLIS);
      return { refreshed: true };
    } catch (error) {
      const reason = error instanceof AnalyticsError ? error.message : "Analytics request failed";
      console.warn(`Relay usage refresh failed: ${reason}`);
      return { refreshed: false, reason };
    }
  }
  liveBytes(since, tag) {
    const row = tag === void 0 ? this.sql.exec("SELECT SUM(bytes) AS total FROM live_usage WHERE at > ?", since).one() : this.sql.exec(
      "SELECT SUM(bytes) AS total FROM live_usage WHERE at > ? AND tag = ?",
      since,
      tag
    ).one();
    return row.total ?? 0;
  }
  prune(now, periodStart) {
    this.sql.exec("DELETE FROM live_usage WHERE at < ?", periodStart);
    this.sql.exec("DELETE FROM live_sessions WHERE updated_at < ?", now - SESSION_RETENTION_MILLIS);
    this.nonces.purge(now);
  }
};

// src/context.ts
function caller(context) {
  return { device: context.device.id, nonce: context.device.nonce };
}
__name(caller, "caller");
function pairStub(env, pairId) {
  return env.PAIRS.get(env.PAIRS.idFromName(pairId));
}
__name(pairStub, "pairStub");
function meterStub(env) {
  return env.METER.get(env.METER.idFromName(METER_NAME));
}
__name(meterStub, "meterStub");

// src/identity.ts
var ID_PATTERN = /^[0-9a-f]{64}$/;
function isDeviceId(value) {
  return ID_PATTERN.test(value);
}
__name(isDeviceId, "isDeviceId");
function isPairId(value) {
  return ID_PATTERN.test(value);
}
__name(isPairId, "isPairId");
var PAIR_ID_LABEL = utf8("mediasync-pair-v1");
async function derivePairId(consumerId, producerId) {
  return toHex(await sha256(concatBytes(PAIR_ID_LABEL, fromHex(consumerId), fromHex(producerId))));
}
__name(derivePairId, "derivePairId");
function pairTag(pairId) {
  return pairId.slice(0, 32);
}
__name(pairTag, "pairTag");

// src/pair-mailbox.ts
import { DurableObject as DurableObject2 } from "cloudflare:workers";
var INTERNAL_DEVICE_HEADER = "x-mediasync-internal-device";
var INTERNAL_NONCE_HEADER = "x-mediasync-internal-nonce";
var MAX_MESSAGE_BYTES = 64 * 1024;
var MESSAGE_TTL_MILLIS = 10 * 60 * 1e3;
var MAX_QUEUED_MESSAGES = 64;
var MAX_QUEUED_BYTES = 256 * 1024;
var MAX_MESSAGES_PER_MINUTE = 120;
var WAKE_MIN_INTERVAL_MILLIS = 20 * 1e3;
var CLOSE_REPLACED = 4001;
var CLOSE_UNPAIRED = 4003;
var CLOSE_POLICY = 1008;
var PAIR_KEY = "pair";
var PairMailbox = class extends DurableObject2 {
  static {
    __name(this, "PairMailbox");
  }
  sql;
  nonces;
  schemaReady = false;
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.nonces = new NonceStore(this.sql);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }
  /** Tables are created on first use, and again after an unpair wiped the storage. */
  ensureSchema() {
    if (this.schemaReady) return;
    this.nonces.ensureSchema();
    this.sql.exec(`CREATE TABLE IF NOT EXISTS messages (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            recipient TEXT NOT NULL,
            body BLOB NOT NULL,
            expires_at INTEGER NOT NULL
        )`);
    this.schemaReady = true;
  }
  record() {
    return this.ctx.storage.get(PAIR_KEY);
  }
  /**
   * Registers the pair, or updates the consumer's FCM token (ADR 0009). The Worker has already checked
   * that [pairId] is derived from the caller (the consumer) and [producer].
   *
   * [fcmToken]: a string sets it, `null` removes it, `undefined` keeps the current one.
   */
  async register(caller2, pairId, producer, fcmToken, now = Date.now()) {
    this.ensureSchema();
    const existing = await this.record();
    if (existing && (existing.consumer !== caller2.device || existing.producer !== producer)) {
      return fail(409, "pair_conflict", "This pair ID belongs to other devices");
    }
    if (!this.nonces.claim(caller2.device, caller2.nonce, now)) return this.replayed();
    const record = existing ?? { pairId, consumer: caller2.device, producer, createdAt: now };
    if (fcmToken === null) delete record.fcmToken;
    else if (fcmToken !== void 0) record.fcmToken = fcmToken;
    await this.ctx.storage.put(PAIR_KEY, record);
    await this.scheduleCleanup();
    return ok({ created: existing === void 0 });
  }
  /** Checks that the caller belongs to the pair (in the required role) and that the request is fresh. */
  async authorize(caller2, need, now = Date.now()) {
    this.ensureSchema();
    const record = await this.record();
    const checked = this.check(record, caller2, need, now);
    if (!checked.ok) return checked;
    await this.scheduleCleanup();
    return checked;
  }
  /**
   * Claims a wake-up for the producer: returns the consumer's FCM token, or why there's none to send.
   * The Worker sends the push itself, so the object isn't kept busy while Google answers.
   */
  async claimWake(caller2, now = Date.now()) {
    this.ensureSchema();
    const record = await this.record();
    const checked = this.check(record, caller2, "producer", now);
    if (!checked.ok) return checked;
    await this.scheduleCleanup();
    if (!record?.fcmToken) {
      return fail(409, "no_push_token", "The consumer hasn't registered for wake-ups");
    }
    const sinceLast = now - (record.lastWakeAt ?? 0);
    if (sinceLast < WAKE_MIN_INTERVAL_MILLIS) {
      const retryAfter = Math.ceil((WAKE_MIN_INTERVAL_MILLIS - sinceLast) / 1e3);
      return fail(429, "wake_throttled", "A wake-up was sent moments ago", { "retry-after": `${retryAfter}` });
    }
    record.lastWakeAt = now;
    await this.ctx.storage.put(PAIR_KEY, record);
    return ok({ token: record.fcmToken });
  }
  /** Forgets [token] after FCM reported it unregistered, unless the consumer already replaced it. */
  async forgetPushToken(token) {
    const record = await this.record();
    if (record?.fcmToken !== token) return;
    delete record.fcmToken;
    await this.ctx.storage.put(PAIR_KEY, record);
  }
  /** Unpairing (PLAN §11, revocation): either device deletes everything the Worker holds for the pair. */
  async unpair(caller2, now = Date.now()) {
    this.ensureSchema();
    const checked = this.check(await this.record(), caller2, "member", now);
    if (!checked.ok) return checked;
    const notice = { type: "error", code: "unpaired", message: "The pair was removed" };
    for (const socket of this.ctx.getWebSockets()) {
      this.trySend(socket, JSON.stringify(notice));
      this.tryClose(socket, CLOSE_UNPAIRED, "unpaired");
    }
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    this.schemaReady = false;
    return ok({ deleted: true });
  }
  /** WebSocket upgrade for the signaling mailbox; the Worker authenticated the caller already. */
  async fetch(request) {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected a WebSocket upgrade", { status: 426 });
    }
    const device = request.headers.get(INTERNAL_DEVICE_HEADER);
    const nonce = request.headers.get(INTERNAL_NONCE_HEADER);
    if (device === null || nonce === null) return new Response("Not authenticated", { status: 401 });
    this.ensureSchema();
    const record = await this.record();
    const checked = this.check(record, { device, nonce }, "member", Date.now());
    if (!checked.ok) {
      return Response.json({ error: { code: checked.code, message: checked.message } }, { status: checked.status });
    }
    for (const old of this.ctx.getWebSockets(device)) this.tryClose(old, CLOSE_REPLACED, "replaced");
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server, [device]);
    const state = { device, windowStart: Date.now(), count: 0 };
    server.serializeAttachment(state);
    const peer = this.peerOf(checked.value.role, checked.value);
    const queued = this.takeQueued(device, Date.now());
    const ready = { type: "ready", peerOnline: this.isOnline(peer, server), queued: queued.length };
    server.send(JSON.stringify(ready));
    for (const message of queued) server.send(message);
    this.broadcastPresence(peer, true, server);
    await this.scheduleCleanup();
    return new Response(null, { status: 101, webSocket: client });
  }
  async webSocketMessage(socket, message) {
    const state = socket.deserializeAttachment();
    const now = Date.now();
    if (now - state.windowStart >= 6e4) {
      state.windowStart = now;
      state.count = 0;
    }
    state.count += 1;
    socket.serializeAttachment(state);
    if (state.count > MAX_MESSAGES_PER_MINUTE) {
      this.tryClose(socket, CLOSE_POLICY, "too many messages");
      return;
    }
    if (typeof message === "string") {
      this.sendError(socket, "binary_only", 'Signaling messages are binary frames; text is for "ping" only');
      return;
    }
    if (message.byteLength > MAX_MESSAGE_BYTES) {
      this.sendError(socket, "message_too_large", `Messages are limited to ${MAX_MESSAGE_BYTES} bytes`);
      return;
    }
    this.ensureSchema();
    const record = await this.record();
    if (!record) {
      this.sendError(socket, "unpaired", "The pair was removed");
      this.tryClose(socket, CLOSE_UNPAIRED, "unpaired");
      return;
    }
    const peer = state.device === record.consumer ? record.producer : record.consumer;
    const online = this.ctx.getWebSockets(peer);
    if (online.length > 0) {
      for (const target of online) this.trySend(target, message);
      return;
    }
    if (!this.enqueue(peer, message, now)) {
      this.sendError(socket, "mailbox_full", "The other device is offline and its mailbox is full");
      return;
    }
    await this.scheduleCleanup();
  }
  async webSocketClose(socket, code, reason) {
    this.tryClose(socket, code === 1005 || code === 1006 ? 1e3 : code, reason);
    const state = socket.deserializeAttachment();
    if (!state) return;
    const record = await this.record();
    if (!record) return;
    const stillOnline = this.ctx.getWebSockets(state.device).some((other) => other !== socket);
    if (stillOnline) return;
    const peer = state.device === record.consumer ? record.producer : record.consumer;
    this.broadcastPresence(peer, false, socket);
  }
  async webSocketError(socket) {
    this.tryClose(socket, 1011, "error");
  }
  /** Deletes expired messages and nonces, so ciphertext doesn't outlive its use. */
  async alarm() {
    this.ensureSchema();
    const now = Date.now();
    this.sql.exec("DELETE FROM messages WHERE expires_at <= ?", now);
    this.nonces.purge(now);
    await this.scheduleCleanup();
  }
  check(record, caller2, need, now) {
    if (!record) return fail(404, "pair_not_found", "No such pair; the consumer registers it after pairing");
    let role;
    if (caller2.device === record.consumer) role = "consumer";
    else if (caller2.device === record.producer) role = "producer";
    else return fail(403, "not_a_member", "This device isn't part of the pair");
    if (need !== "member" && need !== role) return fail(403, `not_${need}`, `Only the ${need} can do this`);
    if (!this.nonces.claim(caller2.device, caller2.nonce, now)) return this.replayed();
    const peer = role === "consumer" ? record.producer : record.consumer;
    return ok({
      pairId: record.pairId,
      role,
      consumer: record.consumer,
      producer: record.producer,
      pushTokenRegistered: record.fcmToken !== void 0,
      peerOnline: this.isOnline(peer)
    });
  }
  replayed() {
    return fail(401, "auth_replayed", "This request was already used");
  }
  peerOf(role, membership) {
    return role === "consumer" ? membership.producer : membership.consumer;
  }
  isOnline(device, except) {
    return this.ctx.getWebSockets(device).some((socket) => socket !== except);
  }
  broadcastPresence(device, online, except) {
    const frame = { type: "peer", online };
    for (const socket of this.ctx.getWebSockets(device)) {
      if (socket !== except) this.trySend(socket, JSON.stringify(frame));
    }
  }
  enqueue(recipient, message, now) {
    this.sql.exec("DELETE FROM messages WHERE expires_at <= ?", now);
    const usage = this.sql.exec(
      "SELECT COUNT(*) AS count, SUM(LENGTH(body)) AS bytes FROM messages WHERE recipient = ?",
      recipient
    ).one();
    if (usage.count >= MAX_QUEUED_MESSAGES || (usage.bytes ?? 0) + message.byteLength > MAX_QUEUED_BYTES) {
      return false;
    }
    this.sql.exec(
      "INSERT INTO messages (recipient, body, expires_at) VALUES (?, ?, ?)",
      recipient,
      message,
      now + MESSAGE_TTL_MILLIS
    );
    return true;
  }
  /** Removes and returns the unexpired messages waiting for [recipient], oldest first. */
  takeQueued(recipient, now) {
    const rows = this.sql.exec(
      "SELECT body FROM messages WHERE recipient = ? AND expires_at > ? ORDER BY seq",
      recipient,
      now
    ).toArray();
    this.sql.exec("DELETE FROM messages WHERE recipient = ?", recipient);
    return rows.map((row) => row.body);
  }
  /** Arms the alarm for the earliest expiry of a queued message or nonce. */
  async scheduleCleanup() {
    const nextMessage = this.sql.exec("SELECT MIN(expires_at) AS next FROM messages").one().next;
    const candidates = [nextMessage, this.nonces.nextExpiry()].filter((t) => t !== null);
    if (candidates.length === 0) return;
    const next = Math.min(...candidates);
    const current = await this.ctx.storage.getAlarm();
    if (current === null || current > next) await this.ctx.storage.setAlarm(next);
  }
  sendError(socket, code, message) {
    const frame = { type: "error", code, message };
    this.trySend(socket, JSON.stringify(frame));
  }
  trySend(socket, data) {
    try {
      socket.send(data);
    } catch {
    }
  }
  tryClose(socket, code, reason) {
    try {
      socket.close(code, reason);
    } catch {
    }
  }
};

// src/handlers/pairs.ts
var MAX_FCM_TOKEN_LENGTH = 4096;
async function registerPair(context) {
  const body = parseJsonObject(context.body);
  const producer = requiredString(body, "producer", 64);
  if (!isDeviceId(producer)) throw badRequest('"producer" must be a device ID: 64 lowercase hex characters');
  if (producer === context.device.id) throw badRequest("A device can't pair with itself");
  const fcmToken = body.fcmToken === null ? null : optionalString(body, "fcmToken", MAX_FCM_TOKEN_LENGTH);
  if (fcmToken === "") throw badRequest('"fcmToken" must not be empty; send null to remove it');
  if (await derivePairId(context.device.id, producer) !== context.pairId) {
    throw new ApiError(400, "pair_id_mismatch", "The pair ID isn't derived from this consumer and producer");
  }
  const result = unwrap(
    await pairStub(context.env, context.pairId).register(caller(context), context.pairId, producer, fcmToken, context.now)
  );
  return json({ pairId: context.pairId, created: result.created }, result.created ? 201 : 200);
}
__name(registerPair, "registerPair");
async function getPair(context) {
  const membership = unwrap(await pairStub(context.env, context.pairId).authorize(caller(context), "member", context.now));
  return json({
    pairId: membership.pairId,
    role: membership.role,
    pushTokenRegistered: membership.pushTokenRegistered,
    peerOnline: membership.peerOnline
  });
}
__name(getPair, "getPair");
async function deletePair(context) {
  unwrap(await pairStub(context.env, context.pairId).unpair(caller(context), context.now));
  return json({ deleted: true });
}
__name(deletePair, "deletePair");
async function openSignaling(context) {
  if (context.request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
    throw new ApiError(426, "websocket_required", "This endpoint needs a WebSocket upgrade");
  }
  const headers = new Headers(context.request.headers);
  headers.delete(INTERNAL_DEVICE_HEADER);
  headers.delete(INTERNAL_NONCE_HEADER);
  headers.set(INTERNAL_DEVICE_HEADER, context.device.id);
  headers.set(INTERNAL_NONCE_HEADER, context.device.nonce);
  return pairStub(context.env, context.pairId).fetch(new Request(context.request.url, { headers }));
}
__name(openSignaling, "openSignaling");

// src/rate-limit.ts
async function enforceLimit(limiter, key) {
  if (limiter === void 0) return;
  const { success } = await limiter.limit({ key });
  if (!success) {
    throw new ApiError(429, "rate_limited", "Too many requests; try again in a minute", { "retry-after": "60" });
  }
}
__name(enforceLimit, "enforceLimit");
function clientKey(request) {
  return `ip:${request.headers.get("cf-connecting-ip") ?? "unknown"}`;
}
__name(clientKey, "clientKey");

// src/turn.ts
var STUN_SERVERS = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] }];
var TURN_API = "https://rtc.live.cloudflare.com/v1/turn/keys";
var TurnError = class extends Error {
  static {
    __name(this, "TurnError");
  }
};
function asIceServer(value) {
  if (typeof value !== "object" || value === null) return null;
  const { urls, username, credential } = value;
  const list = typeof urls === "string" ? [urls] : urls;
  if (!Array.isArray(list) || !list.every((url) => typeof url === "string")) return null;
  const server = { urls: list };
  if (typeof username === "string") server.username = username;
  if (typeof credential === "string") server.credential = credential;
  return server;
}
__name(asIceServer, "asIceServer");
async function mintTurnCredentials(options) {
  const fetcher = options.fetcher ?? fetch;
  const response = await fetcher(
    `${TURN_API}/${encodeURIComponent(options.keyId)}/credentials/generate-ice-servers`,
    {
      method: "POST",
      headers: { authorization: `Bearer ${options.apiToken}`, "content-type": "application/json" },
      body: JSON.stringify({ ttl: options.ttlSeconds, customIdentifier: options.customIdentifier })
    }
  );
  if (!response.ok) throw new TurnError(`TURN credential request failed with HTTP ${response.status}`);
  const body = await response.json();
  const raw = Array.isArray(body.iceServers) ? body.iceServers : [body.iceServers];
  const servers = raw.map(asIceServer).filter((server) => server !== null);
  if (!servers.some((server) => server.username !== void 0 && server.credential !== void 0)) {
    throw new TurnError("TURN credential response had no credentials");
  }
  return servers;
}
__name(mintTurnCredentials, "mintTurnCredentials");

// src/handlers/relay.ts
var DEFAULT_TTL_SECONDS = 3600;
function credentialTtl(env) {
  const ttl = Number(env.TURN_CREDENTIAL_TTL_SECONDS);
  return Number.isInteger(ttl) && ttl >= 60 && ttl <= DEFAULT_TTL_SECONDS ? ttl : DEFAULT_TTL_SECONDS;
}
__name(credentialTtl, "credentialTtl");
async function iceServers(context) {
  const { env } = context;
  await enforceLimit(env.TURN_LIMITER, `turn:${context.device.id}`);
  unwrap(await pairStub(env, context.pairId).authorize(caller(context), "member", context.now));
  const ttlSeconds = credentialTtl(env);
  const stunOnly = /* @__PURE__ */ __name((relay) => json({ iceServers: STUN_SERVERS, ttlSeconds, relay }), "stunOnly");
  const meter = await meterStub(env).view(void 0, context.now);
  if (!meter.relayAvailable) {
    return stunOnly({ available: false, reason: "budget_exhausted", resumesAt: meter.period.end });
  }
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return stunOnly({ available: false, reason: "not_configured" });
  try {
    const servers = await mintTurnCredentials({
      keyId: env.TURN_KEY_ID,
      apiToken: env.TURN_KEY_API_TOKEN,
      ttlSeconds,
      customIdentifier: pairTag(context.pairId)
    });
    return json({ iceServers: servers, ttlSeconds, relay: { available: true } });
  } catch (error) {
    console.warn(`TURN credentials unavailable: ${error instanceof TurnError ? error.message : "request failed"}`);
    return stunOnly({ available: false, reason: "unavailable" });
  }
}
__name(iceServers, "iceServers");
async function getRelayUsage(context) {
  unwrap(await pairStub(context.env, context.pairId).authorize(caller(context), "member", context.now));
  return json(await meterStub(context.env).view(pairTag(context.pairId), context.now));
}
__name(getRelayUsage, "getRelayUsage");
var SESSION_PATTERN = /^[A-Za-z0-9_-]{22}$/;
async function reportRelayUsage(context) {
  const body = parseJsonObject(context.body);
  const session = requiredString(body, "session", 22);
  if (!SESSION_PATTERN.test(session)) throw badRequest('"session" must be 16 bytes in base64url (22 characters)');
  const relayedBytes = requiredCount(body, "relayedBytes");
  unwrap(await pairStub(context.env, context.pairId).authorize(caller(context), "member", context.now));
  return json(await meterStub(context.env).report(pairTag(context.pairId), session, relayedBytes, context.now));
}
__name(reportRelayUsage, "reportRelayUsage");
function admins(env) {
  return new Set(
    (env.RELAY_ADMIN_DEVICE_IDS ?? "").split(",").map((id) => id.trim()).filter((id) => id !== "")
  );
}
__name(admins, "admins");
async function updateRelaySettings(context) {
  if (!admins(context.env).has(context.device.id)) {
    throw new ApiError(403, "not_admin", "This device may not change relay settings");
  }
  const body = parseJsonObject(context.body);
  const meter = meterStub(context.env);
  const settings = unwrap(await meter.updateSettings(context.device.id, context.device.nonce, body, context.now));
  context.ctx.waitUntil(meter.refresh());
  return json(settings);
}
__name(updateRelaySettings, "updateRelaySettings");

// src/fcm.ts
var FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
var DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
var WAKE_TTL_SECONDS = 60;
var FcmConfigError = class extends Error {
  static {
    __name(this, "FcmConfigError");
  }
};
function parseServiceAccount(json2) {
  let value;
  try {
    value = JSON.parse(json2);
  } catch {
    throw new FcmConfigError("FCM_SERVICE_ACCOUNT isn't JSON");
  }
  const { project_id, client_email, private_key, private_key_id, token_uri } = value;
  if (typeof project_id !== "string" || typeof client_email !== "string" || typeof private_key !== "string") {
    throw new FcmConfigError("FCM_SERVICE_ACCOUNT lacks project_id, client_email or private_key");
  }
  const account = {
    projectId: project_id,
    clientEmail: client_email,
    privateKeyPem: private_key,
    tokenUri: typeof token_uri === "string" ? token_uri : DEFAULT_TOKEN_URI
  };
  if (typeof private_key_id === "string") account.privateKeyId = private_key_id;
  return account;
}
__name(parseServiceAccount, "parseServiceAccount");
function pemToDer(pem) {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----/, "").replace(/-----END PRIVATE KEY-----/, "").replace(/\s+/g, "");
  const binary = atob(base64);
  const der = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) der[i] = binary.charCodeAt(i);
  return der;
}
__name(pemToDer, "pemToDer");
async function signAssertion(account, nowSeconds) {
  let key;
  try {
    key = await crypto.subtle.importKey(
      "pkcs8",
      pemToDer(account.privateKeyPem),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"]
    );
  } catch {
    throw new FcmConfigError("FCM_SERVICE_ACCOUNT's private_key isn't a PKCS #8 RSA key");
  }
  const header = { alg: "RS256", typ: "JWT" };
  if (account.privateKeyId) header.kid = account.privateKeyId;
  const claims = {
    iss: account.clientEmail,
    scope: FCM_SCOPE,
    aud: account.tokenUri,
    iat: nowSeconds,
    exp: nowSeconds + 3600
  };
  const unsigned = `${base64UrlEncode(utf8(JSON.stringify(header)))}.${base64UrlEncode(utf8(JSON.stringify(claims)))}`;
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, utf8(unsigned)));
  return `${unsigned}.${base64UrlEncode(signature)}`;
}
__name(signAssertion, "signAssertion");
var tokenCache = /* @__PURE__ */ new Map();
async function accessToken(account, fetcher, nowMillis) {
  const cached = tokenCache.get(account.clientEmail);
  if (cached && cached.expiresAtMillis > nowMillis) return cached.value;
  const assertion = await signAssertion(account, Math.floor(nowMillis / 1e3));
  const response = await fetcher(account.tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString()
  });
  if (!response.ok) throw new Error(`Google token request failed with HTTP ${response.status}`);
  const body = await response.json();
  if (typeof body.access_token !== "string") throw new Error("Google token response had no access_token");
  const lifetimeSeconds = typeof body.expires_in === "number" ? body.expires_in : 3600;
  tokenCache.set(account.clientEmail, {
    value: body.access_token,
    expiresAtMillis: nowMillis + (lifetimeSeconds - 60) * 1e3
  });
  return body.access_token;
}
__name(accessToken, "accessToken");
async function sendWake(options) {
  const fetcher = options.fetcher ?? fetch;
  const now = options.now ?? Date.now();
  const url = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(options.account.projectId)}/messages:send`;
  const message = {
    message: {
      token: options.token,
      data: options.data,
      android: { priority: "HIGH", ttl: `${WAKE_TTL_SECONDS}s`, collapse_key: options.collapseKey }
    }
  };
  for (let attempt = 0; attempt < 2; attempt++) {
    let bearer;
    try {
      bearer = await accessToken(options.account, fetcher, now);
    } catch (error) {
      if (error instanceof FcmConfigError) throw error;
      return "failed";
    }
    const response = await fetcher(url, {
      method: "POST",
      headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
      body: JSON.stringify(message)
    });
    if (response.ok) return "sent";
    if (response.status === 401 && attempt === 0) {
      tokenCache.delete(options.account.clientEmail);
      continue;
    }
    if (response.status === 404 || response.status === 400) {
      const text = await response.text();
      if (text.includes("UNREGISTERED") || text.includes("registration token")) return "unregistered";
    }
    return "failed";
  }
  return "failed";
}
__name(sendWake, "sendWake");

// src/handlers/wake.ts
var MAX_PAYLOAD_LENGTH = 2048;
var PAYLOAD_PATTERN = /^[A-Za-z0-9_-]*$/;
function serviceAccount(env) {
  if (!env.FCM_SERVICE_ACCOUNT) {
    throw new ApiError(503, "push_not_configured", "This rendezvous has no FCM service account");
  }
  try {
    return parseServiceAccount(env.FCM_SERVICE_ACCOUNT);
  } catch (error) {
    console.warn(error instanceof FcmConfigError ? error.message : "FCM_SERVICE_ACCOUNT is invalid");
    throw new ApiError(503, "push_not_configured", "This rendezvous' FCM service account is invalid");
  }
}
__name(serviceAccount, "serviceAccount");
function sender(env) {
  if (env.FCM_SERVICE_ACCOUNT) {
    const account = serviceAccount(env);
    return async (token, data, collapseKey) => {
      try {
        return await sendWake({ account, token, data, collapseKey });
      } catch (error) {
        if (!(error instanceof FcmConfigError)) throw error;
        console.warn(error.message);
        throw new ApiError(503, "push_not_configured", "This rendezvous' FCM service account is invalid");
      }
    };
  }
  if (env.WAKE_RELAY_URL) return (token, data, collapseKey) => relayWake(env.WAKE_RELAY_URL, token, data, collapseKey);
  throw new ApiError(503, "push_not_configured", "This rendezvous has no FCM service account or wake relay");
}
__name(sender, "sender");
async function relayWake(relayUrl, token, data, collapseKey) {
  let response;
  try {
    response = await fetch(new URL("/v1/relay/wake", relayUrl), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token, data, collapseKey })
    });
  } catch {
    return "failed";
  }
  if (response.ok) return "sent";
  if (response.status === 410) return "unregistered";
  return "failed";
}
__name(relayWake, "relayWake");
async function wake(context) {
  const body = parseJsonObject(context.body);
  const payload = optionalString(body, "payload", MAX_PAYLOAD_LENGTH);
  if (payload !== void 0 && !PAYLOAD_PATTERN.test(payload)) throw badRequest('"payload" must be base64url');
  const send = sender(context.env);
  const pair = pairStub(context.env, context.pairId);
  const { token } = unwrap(await pair.claimWake(caller(context), context.now));
  const data = { type: "wake", v: "1", pair: context.pairId };
  if (payload !== void 0) data.payload = payload;
  const outcome = await send(token, data, `wake-${pairTag(context.pairId)}`);
  switch (outcome) {
    case "sent":
      return json({ sent: true });
    case "unregistered":
      await pair.forgetPushToken(token);
      throw new ApiError(410, "push_token_unregistered", "The consumer's FCM token is no longer valid");
    case "failed":
      throw new ApiError(502, "push_failed", "FCM didn't accept the wake-up; try again later");
  }
}
__name(wake, "wake");
var MAX_TOKEN_LENGTH = 4096;
var MAX_DATA_ENTRIES = 8;
var MAX_DATA_LENGTH = 3072;
var MAX_COLLAPSE_KEY_LENGTH = 100;
async function relayedWake(env, body) {
  if (env.WAKE_RELAY_ENABLED !== "true") throw new ApiError(404, "not_found", "No such endpoint");
  const account = serviceAccount(env);
  const fields = parseJsonObject(body);
  const token = optionalString(fields, "token", MAX_TOKEN_LENGTH);
  const collapseKey = optionalString(fields, "collapseKey", MAX_COLLAPSE_KEY_LENGTH);
  const data = fields.data;
  if (!token || !collapseKey) throw badRequest('"token" and "collapseKey" are required');
  if (typeof data !== "object" || data === null || Array.isArray(data)) throw badRequest('"data" must be an object');
  const entries = Object.entries(data);
  const strings = entries.every(([, value]) => typeof value === "string");
  if (!strings || entries.length > MAX_DATA_ENTRIES || JSON.stringify(data).length > MAX_DATA_LENGTH) {
    throw badRequest('"data" must be a few short strings');
  }
  const key = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
  const hex = Array.from(key.slice(0, 16), (b) => b.toString(16).padStart(2, "0")).join("");
  await enforceLimit(env.TURN_LIMITER, `wake:${hex}`);
  let outcome;
  try {
    outcome = await sendWake({ account, token, data, collapseKey });
  } catch (error) {
    if (!(error instanceof FcmConfigError)) throw error;
    console.warn(error.message);
    throw new ApiError(503, "push_not_configured", "The wake relay's FCM service account is invalid");
  }
  switch (outcome) {
    case "sent":
      return json({ sent: true });
    case "unregistered":
      throw new ApiError(410, "push_token_unregistered", "The FCM token is no longer valid");
    case "failed":
      throw new ApiError(502, "push_failed", "FCM didn't accept the wake-up; try again later");
  }
}
__name(relayedWake, "relayedWake");

// src/router.ts
var PAIR = "(?<pairId>[0-9a-f]{64})";
var ROUTES = [
  { pattern: new RegExp(`^/v1/pairs/${PAIR}$`), methods: { PUT: registerPair, GET: getPair, DELETE: deletePair } },
  { pattern: new RegExp(`^/v1/pairs/${PAIR}/signal$`), methods: { GET: openSignaling } },
  { pattern: new RegExp(`^/v1/pairs/${PAIR}/ice-servers$`), methods: { POST: iceServers } },
  { pattern: new RegExp(`^/v1/pairs/${PAIR}/wake$`), methods: { POST: wake } },
  { pattern: new RegExp(`^/v1/pairs/${PAIR}/relay-usage$`), methods: { GET: getRelayUsage, POST: reportRelayUsage } },
  { pattern: /^\/v1\/relay\/settings$/, methods: { PUT: updateRelaySettings } }
];
function match(url, method) {
  for (const route of ROUTES) {
    const found = route.pattern.exec(url.pathname);
    if (!found) continue;
    const handler = route.methods[method];
    if (!handler) {
      throw new ApiError(405, "method_not_allowed", `${method} isn't supported here`, {
        allow: Object.keys(route.methods).join(", ")
      });
    }
    const pairId = found.groups?.pairId ?? "";
    if (pairId !== "" && !isPairId(pairId)) throw new ApiError(404, "not_found", "No such endpoint");
    return { handler, pairId };
  }
  throw new ApiError(404, "not_found", "No such endpoint");
}
__name(match, "match");
async function handleRequest(request, env, ctx) {
  try {
    const url = new URL(request.url);
    if (url.pathname === "/v1/health") {
      if (request.method !== "GET") throw new ApiError(405, "method_not_allowed", "Use GET", { allow: "GET" });
      return json({ status: "ok" });
    }
    if (url.pathname === "/v1/relay/wake") {
      if (request.method !== "POST") throw new ApiError(405, "method_not_allowed", "Use POST", { allow: "POST" });
      await enforceLimit(env.IP_LIMITER, clientKey(request));
      return await relayedWake(env, await readBody(request));
    }
    const { handler, pairId } = match(url, request.method);
    await enforceLimit(env.IP_LIMITER, clientKey(request));
    const body = await readBody(request);
    const now = Date.now();
    const device = await authenticate(request, body, now);
    await enforceLimit(env.DEVICE_LIMITER, `device:${device.id}`);
    return await handler({ request, env, ctx, url, body, device, pairId, now });
  } catch (error) {
    if (error instanceof ApiError) return errorResponse(error);
    console.error(`Unhandled error: ${error instanceof Error ? error.message : "unknown"}`);
    return errorResponse(new ApiError(500, "internal", "Something went wrong"));
  }
}
__name(handleRequest, "handleRequest");

// src/index.ts
var index_default = {
  fetch(request, env, ctx) {
    return handleRequest(request, env, ctx);
  },
  /** Every 15 minutes: read the authoritative relay usage from Cloudflare's analytics (PLAN §8.5). */
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(meterStub(env).refresh(controller.scheduledTime));
  }
};
export {
  PairMailbox,
  UsageMeter,
  index_default as default
};
//# sourceMappingURL=index.js.map
