# 0003. Serverless rendezvous, WebRTC and a metered relay for internet sync

**Status:** Accepted · 2026-10-13

## Context

The household has no server hardware. Phones sit behind NAT or carrier NAT and change addresses, so a phone can't reliably be reached from the internet on its own.

## Decision

- A Cloudflare Worker with a Durable Object (free tier) acts as rendezvous: it exchanges end-to-end encrypted connection hints and sends wake-up pushes through Firebase Cloud Messaging.
- Phones connect directly with WebRTC (ICE hole punching). When that's impossible, traffic goes through Cloudflare TURN, which forwards ciphertext only.
- The relay is **metered**: the app shows usage against the 1,000 GB monthly free allowance, and the Worker stops issuing relay credentials at a budget (default 950 GB).
- Local-network-only use needs none of these services.

## Consequences

- No hardware, and €0 at family scale.
- Two external services (Cloudflare, Firebase), used only when internet sync or wake-ups are enabled.
- If the app is published widely, one Cloudflare account's allowance is shared. That decision is deferred (PLAN.md §18).
