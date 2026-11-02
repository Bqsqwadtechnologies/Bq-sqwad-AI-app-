# APP_SIDE_RELEASE_REPORT

## Changes verified on main
- `BQSqwadApp.kt` is the complete app source (approximately 490 KB), not a path stub.
- Main chat retains `ModelSelector`, `PlusAttachmentButton`, selected model key propagation, and `EntitlementsClient` fallback.
- Composer text entry and attachment controls remain enabled during balance refresh. Client-side pre-send balance-loading rejection was removed; the Worker remains the billing authority.
- `cloudflare-worker/src/index.js` now imports the presence helper and serves authenticated `POST /presence/ping`, writing `presence/{uid}`.
- `BusinessWorkspaceScreen` now exposes Playbooks and Connect tools navigation and mounts `BusinessPlaybookScreen` and `PlanToolsConnectScreen`. Tool connection actions open the website Business Tool Studio.
- The broad Worker catalog keeps KYC/BVN/NIN optional and supports full-catalog fallback when no plan tool IDs are supplied.
- Worker `index.js` now maps `modelKey=bq-7-star` (default) to `openai/gpt-6-luna` and `modelKey=bq-codron` to `openai/gpt-5.6-luna` in both streaming and non-streaming chat paths. A supplied published `modelRegistryId` keeps precedence.
- Gemini fallback is **not complete**: `orchestrator-native.js` is not wired, and the current `runProvider` / `runProviderStream` implementations attempt only the primary provider even when fallback IDs are supplied. Cost-gated fallback needs implementation and tests before release.

## Commits
- Composer recovery: `72cb451056f3fffd8ad58f504a5dcdaf3bab3477`
- Presence route: `781859a7c9c50d9ee63dfbf269756518fe718a15`
- Model-key routing: `6d1c56867eb71cbef4f0b88953ca37d9b4d530af`
- Business workspace navigation: `b07eca489ca4354cd20cb91dd82bd6665520025b`, handoff refinement: `3b0fa0a00713e61c520ddbde0e6cd12510f711dc`
- Android Business workspace navigation: `b07eca489ca4354cd20cb91dd82bd6665520025b`

## Checks performed
- GitHub Contents API read-back confirmed the full Android file and the presence route commit.
- Static content checks confirmed the composer no longer contains the live-credit waiting rejection or placeholder.
- No Gradle, Worker npm, or website npm test/build command was executed in this hosted GitHub editing environment.

## Remaining / unverified
- Worker sharded `usageDaily` helper exists but is not wired to all metered feature settlement paths.
- Daily free-credit notification/FCM path is not verified end-to-end.
- `orchestrator-native.js` is not imported; current fallback is the existing provider-router fallback. Stream fallback after partial output requires a separate safe design.
- Business tool catalog / per-plan entitlement enforcement is not fully connected to the generic Composio OAuth route; the Business Studio is currently a catalog-to-Settings handoff, not a fully integrated per-tool Composio connector state. Some catalog entries are not in the Worker connector allowlist.
- Business playbook and tool-connect navigation changes have not been compiled with Gradle.
- Teams v2 team-credit-pool parity, admin theme hydration/no-flash, live work timer/phase events, notifications inbox/MessagingStyle, organization AI parity, Contact Sales grants, encrypted vault writes/hash-chain integration, inbox staff pagination, export pipeline, storage meter real data, and training redaction/exclusion coverage remain unverified or incomplete.
- Website Business Studio currently contains placeholder storage/expiry display values; production values must come from server APIs before release.
- Full CI and production secret/configuration checks remain unverified.


## Teams membership concurrency update — 2026-11-02
- Worker Teams join/approve/leave now use Firestore update-time compare-and-set transitions. Concurrent duplicate transitions do not each change the denormalized member count; leaving marks the membership as `left` and removes the user's active membership index. Source: `cloudflare-worker/src/teams-sqwad-native.js`, commit `ddcbf3205f67703a4e6e26b00f421399543b0c01`.
- Static contract coverage was added in `cloudflare-worker/test/team-membership-contract.test.js`, commit `7bd97080fa1b9fbc87b2a27089aa96d39b365640`. Tests were not executed.
- The server has no hard-coded 200-seat ceiling, but 500-member load, pagination, migration/index correctness and website↔Android round-trip still require testing.



## My Tasks and notification preferences update — 2026-11-02
- Native My Tasks screen is now present and wired to authenticated Worker endpoints. It lists only tasks assigned to the signed-in UID and permits open/in-progress/blocked/done transitions after server-side assignment checks. Feature Hub navigation and privacy disclosure are connected. Commits: `2099ada6`, `613e79f5`, `e2cbed48`, `7e227d85`, `ae17f1ed`, `42d30af3`.
- Android Settings reads/writes the account-scoped notification preferences API; Worker admin broadcast filters accounts with push disabled. Commits: `59aecc86`, `4432e5be`, `222dd315`, `5eecdd59`, `7aa547ee`.
- Worker My Tasks and notification contract assertions added in `cloudflare-worker/test/native-workspace-contract.test.js`, commit `b0e93bbb`. Test suite and Gradle build were NOT run.
- No release readiness, Android build success, Worker test pass, live auth validation or device QA is claimed.
