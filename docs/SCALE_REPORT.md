# BQ Sqwad AI Scale Report

## Measurement status

**No load-test capacity number is claimed in this report.** The repository was edited through GitHub's contents API in this work session; there was no attached checkout/runtime in which to install dependencies, run k6/Artillery against the deployed Worker, inspect provider quotas, or measure Firestore and Cloudflare throughput. Reporting an estimated concurrency number as measured capacity would be misleading.

## Required test matrix

Run these tests against a staging Worker and test Firebase project before production load testing. Do not use real user accounts or production credit balances.

| Route class | Scenario | Metrics to record |
| --- | --- | --- |
| Chat | Short non-streaming response; concurrent independent users | p50/p95/p99 latency, success rate, provider 429/5xx, token settlement errors |
| Streaming | Long response, client disconnect, reconnect, interrupted stream | time to first token, stream completion rate, disconnect handling, reservation refund accuracy |
| History and sync | Concurrent list, restore and sync polling while chat is active | latency, chat error rate under polling, Firestore reads/writes |
| Team messages | Concurrent room messages, @BQ-triggered AI response, multiple readers | message fan-out latency, AI latency, pool settlement correctness, per-member usage consistency |
| Connectors | Read-only calls and authorized writes in parallel with chat | tool latency, expired-session recovery, provider failures, chat latency impact |

## Load ramp

1. Validate one virtual user and confirm that every response, reservation, settlement, refund, and history write is correct.
2. Ramp to 10, 25, 50, 100, 250, 500, and 1,000 virtual users, stopping at the first sustained error-rate or latency regression.
3. Hold each level long enough to observe provider quota windows and Firestore contention; record duration, request rate, active virtual users, and route mix.
4. Repeat with streaming, history/sync polling, team messages, and connector actions enabled.
5. Fix the first bottleneck and repeat the same test profile. Do not extrapolate results to tens of millions of simultaneous users without measured evidence.

## Release blockers / unknowns

- Cloudflare Worker plan and account-level request/concurrency limits have not been measured in this run.
- AI provider account rate limits, key-pool availability and per-model quotas have not been measured in this run.
- Firestore sustained read/write throughput and contention under the target data model have not been measured in this run.
- The current in-memory rate limiter must not be described as globally consistent across Worker isolates. Confirm the deployed rate-limiting binding or Durable Object implementation before testing global limits.
- No k6/Artillery result artifact was available to inspect; this report therefore has no measured capacity result.

## Acceptance criteria

- No incorrect fixed one-credit text-chat settlement.
- All failed/interrupted generations release unused reservations.
- History and sync polling do not consume the chat rate-limit bucket.
- No cross-user or cross-team data leakage.
- Team pool and per-member usage totals reconcile to settled provider-token usage.
- p95/p99 latency and error rates are recorded for every ramp level, with the first bottleneck and post-fix rerun evidence attached before claiming a capacity target.
