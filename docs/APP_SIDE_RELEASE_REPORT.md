# APP_SIDE_RELEASE_REPORT

## Changes verified on main
- `BQSqwadApp.kt` is the complete app source (approximately 490 KB), not a path stub.
- Main chat retains `ModelSelector`, `PlusAttachmentButton`, selected model key propagation, and `EntitlementsClient` fallback.
- Composer text entry and attachment controls remain enabled during balance refresh. Client-side pre-send balance-loading rejection was removed; the Worker remains the billing authority.
- `cloudflare-worker/src/index.js` now imports the presence helper and serves authenticated `POST /presence/ping`, writing `presence/{uid}`.
- `BusinessWorkspaceScreen` now exposes Playbooks and Connect tools navigation and mounts `BusinessPlaybookScreen` and `PlanToolsConnectScreen`. Tool connection actions open the website Business Tool Studio.
- The broad Worker catalog keeps KYC/BVN/NIN optional and supports full-catalog fallback when no plan tool IDs are supplied.
- Existing Worker `runProvider` / `runProviderStream` paths already accept admin-configured fallback provider lists; the separate `orchestrator-native.js` helper is not wired into the live AI path.

## Commits
- Composer recovery: `72cb451056f3fffd8ad58f504a5dcdaf3bab3477`
- Presence route: `781859a7c9c50d9ee63dfbf269756518fe718a15`
- Android Business workspace navigation: `b07eca489ca4354cd20cb91dd82bd6665520025b`

## Checks performed
- GitHub Contents API read-back confirmed the full Android file and the presence route commit.
- Static content checks confirmed the composer no longer contains the live-credit waiting rejection or placeholder.
- No Gradle, Worker npm, or website npm test/build command was executed in this hosted GitHub editing environment.

## Remaining / unverified
- Worker sharded `usageDaily` helper exists but is not wired to all metered feature settlement paths.
- Daily free-credit notification/FCM path is not verified end-to-end.
- `orchestrator-native.js` is not imported; current fallback is the existing provider-router fallback. Stream fallback after partial output requires a separate safe design.
- Business tool catalog / per-plan entitlement enforcement is not fully connected to the generic Composio OAuth route; current generic connector sessions remain separate.
- Business playbook and tool-connect navigation changes have not been compiled with Gradle.
- Teams v2 team-credit-pool parity, admin theme hydration/no-flash, live work timer/phase events, notifications inbox/MessagingStyle, organization AI parity, Contact Sales grants, encrypted vault writes/hash-chain integration, inbox staff pagination, export pipeline, storage meter real data, and training redaction/exclusion coverage remain unverified or incomplete.
- Website Business Studio currently contains placeholder storage/expiry display values; production values must come from server APIs before release.
- Full CI and production secret/configuration checks remain unverified.
