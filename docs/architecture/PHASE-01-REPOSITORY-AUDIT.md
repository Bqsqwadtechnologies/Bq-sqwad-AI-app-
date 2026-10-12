# BQ Sqwad AI — Phase 1 Repository & Product Audit

## Scope

This phase establishes the source-of-truth map for the new native BQ Sqwad AI application.

- New repository: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`
- Branch: `main`
- Application package: `com.bqsqwad.ai`
- Product: BQ Sqwad AI
- Company: BQ Sqwad Technologies
- Architecture target: native Android Kotlin
- Old repository: `Bqsqwadtechnologies/Bq-sqwad-AI-`

The new repository was checked before implementation. It is currently empty. Therefore no old application implementation has been copied into it.

## Migration rule

The old repository is a reference for requirements, product behavior, plan/catalogue information, privacy requirements, and integration contracts. Its web implementation is not being copied into the new application.

The new application will be designed and implemented as a separate native product.

## Explicitly excluded from migration

Do not copy:

- React/TypeScript web UI
- web routes
- web service workers
- Capacitor/WebView shell
- old MainActivity
- old Gradle/build configuration
- old GitHub Actions/CI
- old APK/AAB workflow
- browser-only APIs
- old Firebase client configuration
- Firebase JSON/configuration
- web-specific state management
- old logo/brand asset
- old provider client implementations
- old payment secrets
- old API secrets

## Existing old-product areas identified

The old repository contains product areas for:

- authentication and account management
- age/account onboarding
- main AI chat
- AI usage
- credits
- subscriptions
- Paystack payments
- receipts and billing
- referrals
- privacy and consent
- account deletion
- settings
- Web Search
- AI camera
- AI voice
- companion
- Life Matters
- learning
- business
- Business plans and enterprise
- Business assistants/agents
- Business knowledge and workflows
- social/channel integrations
- AI image/video generation
- translation
- Truth Guard
- studio
- notifications
- admin control
- analytics/monitoring
- AI provider configuration
- AI model/provider abstractions
- memory
- device capabilities
- training/agent-lab concepts

## Privacy source of truth

The old website has a dedicated privacy-policy route and a canonical public URL:

`https://bqsqwadai.com/privacy-policy`

The new Android application will link to the website privacy center rather than maintaining a competing full privacy-policy copy inside the APK.

The website privacy center will be extended to cover the native application's new capabilities where required.

Feature-specific deep links can be added to the website privacy center, for example:

- `#camera`
- `#microphone`
- `#screen-awareness`
- `#location`
- `#ai-training`
- `#payments`
- `#data-deletion`

## Privacy and AI-training rule

The product must distinguish ordinary product processing from an explicitly controlled AI-improvement/training program.

Training eligibility must pass:

1. consent/eligibility check
2. data minimization
3. sensitive-data filtering
4. credential/secret removal
5. de-identification where appropriate
6. dataset validation
7. administrator approval
8. evaluation
9. model/version approval
10. controlled deployment

Credentials and authentication secrets must never become training material.

The BQ Sqwad AI Agent will be trained about its own product architecture, capabilities, settings, tools, supported integrations, operational rules, and limitations through an administrator-controlled knowledge/training system. It must not be expected to infer its own configuration blindly.

Supported training/reference inputs are planned to include approved:

- text
- documents
- images
- videos
- photos
- reference files
- audio/voice
- structured product documentation
- configuration documentation
- code/repository documentation where authorized

## Main Chat boundary

Main Chat is its own workspace.

It is not the UI for every other AI feature.

Main Chat supports:

- fast text responses
- voice-to-text input
- optional response speech
- image generation
- separate video mode
- attachments
- copy
- share
- resend
- report
- Web Search invocation when current information is required

Other major features must open their own dedicated workspace.

## Dedicated AI workspaces

The following must have separate screens/workspaces rather than returning to Main Chat:

- Web Search Intelligence
- AI Live Camera
- AI Live Voice
- Personal Intelligence
- Life Matters
- Learning Intelligence
- Business Intelligence
- future BQ Sqwad Codron plugin

Each workspace has its own navigation, controls, state, pricing/entitlement presentation, loading/error states, and settings relevant to that feature.

## AI Live Camera

Planned capability boundary:

- camera capture
- live vision
- object/scene understanding
- visual questions
- real-time assistance
- device capability awareness
- device permissions
- permitted device actions
- location assistance
- voice direction
- response voice controls
- screen awareness when Android permits it and the user explicitly grants the required system consent

Screen capture must use Android's user-consent MediaProjection mechanism. Android requires user consent for screen capture and, on newer Android versions, consent is required for each capture session. The app must stop correctly if the projection token is revoked.

## AI Live Voice

AI Live Voice is a separate real-time voice conversation.

It is not presented as a voice-to-text or text-to-speech utility.

Planned controls:

- real-time conversation
- interruption/barge-in
- voice selection
- speaker/output selection
- microphone mute
- AI speaking mute
- conversation history
- voice chat integration
- fast response

## Business architecture

Business is a dedicated product workspace.

The old catalogue contains Business capabilities such as:

- assistants
- knowledge
- widget
- inbox
- analytics
- social/channel integrations
- email
- API
- CRM
- leads
- booking
- voice
- calls
- workflows
- documents
- departments
- team permissions
- business planning
- business intelligence
- advanced capabilities
- enterprise/custom arrangements

The new Business dashboard will prioritize:

1. current subscription/entitlement
2. overview
3. connections
4. agents
5. knowledge
6. workflows
7. intelligence
8. analytics
9. settings

Enterprise is a custom Business arrangement, not a normal self-serve tier.

## Business agents

Plan configuration controls the permitted number of agents and capabilities.

Each agent can have:

- purpose
- instructions
- knowledge
- tools
- channel connections
- permissions
- training/reference data
- usage
- analytics

The number of agents must come from the live plan configuration, not an immutable APK constant.

## Pricing and credits

The old repository contains a centralized Paystack plan catalogue for:

- credit subscriptions
- Business subscriptions
- Learning subscriptions
- Companion subscriptions

It also contains Business pricing configuration with tiers, limits, capabilities, add-ons, trials, and enterprise/custom arrangements.

The new application will not hardcode those prices as permanent business logic.

The target architecture is:

**Admin configuration -> server-side catalogue -> app presentation -> entitlement/credit engine -> payment provider**

The app may cache configuration for resilience, but server-authoritative values determine access and billing.

## Payment security

Payment provider secrets must remain server-side.

The new Android app must never contain a Paystack secret key or other provider secret.

Admin may manage provider configuration through a secure admin system, but entering a provider secret must store it in a secure backend secret store, not in the APK or ordinary client database.

Provider abstraction must permit future payment providers without redesigning the purchase UI.

## Admin control

The admin system is completely separate from the user experience.

Primary authorized administrator identifier supplied for this project:

`isaacoshiomole0@gmail.com`

Admin capabilities will eventually include:

- users
- plans
- prices
- credits
- subscriptions
- payments
- payment providers
- AI providers
- model configuration
- Web Search
- Live Camera configuration
- Live Voice configuration
- Business plans
- Learning configuration
- feature flags
- testing mode
- notifications
- referrals
- privacy configuration
- training datasets
- BQ Sqwad AI Agent knowledge
- future plugins
- incidents
- audit logs

User-facing screens must not expose internal provider architecture, admin-only settings, secret configuration, or private operational details.

## Remote/live configuration requirement

Configuration that can safely be changed server-side must not be hardcoded into the APK.

Examples:

- prices
- credit costs
- plans
- trials
- feature availability
- provider activation
- model selection
- Business limits
- agent limits
- supported integrations
- promotions
- notification configuration

A change made by an authorized admin should propagate to the user experience through the application's live/remote configuration system, subject to caching and connectivity.

## Authentication and backend separation

Authentication will be rebuilt for the new native architecture.

The old Firebase client configuration is not being copied.

The new application must have an explicit authentication/backend contract so that:

- user identity is secure
- admin identity is separate
- tokens are handled securely
- user data belongs to the correct account
- configuration is server-authoritative
- user changes can synchronize with the website's shared account/product backend where the final backend contract permits it

The app itself must remain native; synchronization with the website does not mean embedding the website in the app.

## New logo

The old logo will not be copied.

The new logo will be supplied separately by the product owner and integrated after it is provided.

The theme system remains logo-capable across all themes.

## Phase 1 acceptance criteria

Phase 1 is considered structurally complete when:

- the new repository remains free of old web implementation
- the feature inventory is documented
- migration boundaries are documented
- privacy source-of-truth is documented
- training/data rules are documented
- Main Chat and dedicated workspaces are separated
- Business/Enterprise boundaries are documented
- pricing/credit architecture is documented
- provider/payment security boundaries are documented
- admin/user separation is documented
- live configuration requirements are documented
- the new-logo rule is documented
- no Gradle/build/CI/APK files are introduced by this phase

## Next implementation boundary

The next phase is the professional native application architecture and design system. It must create dedicated feature workspaces instead of routing every feature back into Main Chat.

Build configuration remains intentionally untouched during the feature architecture work.


---

## Current audit addendum — 2026-10-12

This addendum supersedes outdated historical statements above that the repository is empty or that the new logo is still waiting to be supplied. The current repository contains a native Android app and an established Cloudflare Worker. This is a source audit, not a device/backend PASS.

### Current architecture and change-control
- Repository: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`; branch: `main`; package: `com.bqsqwad.ai`.
- Android is Kotlin/Jetpack Compose. The app API configuration points to the Cloudflare Worker in `cloudflare-worker/`; `functions/` is legacy/reference code, not the app's configured API base.
- Current reviewed HEAD before this audit update: `e1f17c7b24eb32ca23291cd75078149beff003a5`.
- GitHub commit status has no completed checks (`total_count: 0`). No build, Worker test suite, deployment, or device test was run during this audit. No GitHub Actions workflow was triggered.
- Preserve the native app, logo asset, Firebase/Worker architecture, signing, package ID, product IDs, security gates, and credit reserve/commit/reverse semantics.

### Feature audit
Status labels deliberately distinguish source presence from execution.

| # | Feature | Status label | Finding / remaining verification |
|---:|---|---|---|
| 1 | Sign-in, sign-up, consent, sign-out, deletion | WIRED IN CODE (not run) | Firebase/auth and consent paths exist; account lifecycle and provider callbacks need device/backend verification. |
| 2 | Main chat, streaming, stop, regenerate, copy, attachments, private chat, history | WIRED IN CODE (not run) | Main flow, response actions, history save/reopen and credit refresh paths exist. PDF/Office extraction and end-to-end persistence remain gaps. |
| 3 | Image generation/editing | WIRED IN CODE (not run) | Worker generation route, input validation, credit reversal, chat preview and durable-image upload paths exist. Provider model/account access, Storage rules, deployed secrets and visible result must be tested. |
| 4 | Video generation | WIRED IN CODE (not run) | Worker generation paths exist; provider access, output retrieval, timeout and client display are unverified. |
| 5 | Web search/deep research | WIRED IN CODE (not run) | Dedicated UI/API paths exist; live search credentials, source quality and failure handling are unverified. |
| 6 | File/document analysis | PARTIAL | Image and supported text-like files are handled; PDF and Office extraction are not implemented in the current reader. |
| 7 | Advanced capability picker | WIRED IN CODE (not run) | Capability/model selection is present; each selection-to-request mapping requires runtime checks. |
| 8 | AI Live Voice | WIRED IN CODE (not run) | Speech recognition + AI response + TTS source path exists. Do not label it low-latency full-duplex audio; microphone interruption and release require device testing. |
| 9 | AI Live Camera | WIRED IN CODE (not run) | Camera2 preview, lens switching, flashlight, frame sampling, diagnostics and retry paths are present in recent commits. Real front/back camera, vision response and lifecycle are unverified. |
| 10 | Screen sharing | WIRED IN CODE (not run) | MediaProjection user-consent and stop/revocation paths exist; Android-version and device testing remain. |
| 11 | Device commands | WIRED IN CODE (not run) | Allowlisted command paths exist; each supported action and permission handoff needs device verification. |
| 12 | Learning Studio, exam add-ons, downloads | WIRED IN CODE (not run) | Dedicated workspace and API paths exist; plan entitlements, downloads and progress persistence are unverified. |
| 13 | TruthGuard AI | WIRED IN CODE (not run) | Dedicated workspace/source paths exist; provider execution and result handling are unverified. |
| 14 | Life Mathis plan gate | WIRED IN CODE (not run) | Dedicated plan-gate path exists; server entitlement consistency is unverified. |
| 15 | AI Companion | WIRED IN CODE (not run) | Dedicated workspace and memory controls exist; persistence and plan enforcement are unverified. |
| 16 | Personal Intelligence | WIRED IN CODE (not run) | Dedicated workspace/API paths exist; saved state and server behavior are unverified. |
| 17 | Business AI | PARTIAL | Workspace, assistants/knowledge/training and analytics paths exist. Named external channels are not all proven to have complete authenticated platform adapters. |
| 18 | Organization AI/Studio | PARTIAL | Production metadata and a YouTube-specific RTMP path exist in source. Do not imply equivalent TikTok/other-platform broadcast control without a complete supported adapter and device test. |
| 19 | History | WIRED IN CODE (not run) | List/open/continue/delete paths exist; ownership and persisted conversation continuity require runtime verification. |
| 20 | Plans & Credits/catalog | WIRED IN CODE (not run) | Canonical Worker catalog and client parsing exist; deployed Firestore configuration, plan completeness and purchase eligibility need verification. |
| 21 | Purchase, restore, backend verification | WIRED IN CODE (not run) | Google Play Billing and Worker verification paths exist; real purchases/restores are not tested here. |
| 22 | Trial/daily credits, warnings, reserve/commit/reverse | WIRED IN CODE (not run) | Credit/usage ledger and reversal paths exist in source. Admin-configured daily grant values, expiry/90% notices and live balance accuracy need explicit runtime tests. |
| 23 | Settings, account protection, legal, notifications | PARTIAL | Settings and protection/legal surfaces exist; individual passkey/biometric and notification flows are not all verified. |
| 24 | Admin Control Center and test mode | WIRED IN CODE (not run) | Admin-only routes, model controls, readiness diagnostics and self-test code exist. Confirm super-admin enforcement against a live Worker; self-test readiness is not proof of actual paid output. |
| 25 | Push notifications | WIRED IN CODE (not run) | Messaging service/source path exists; token registration and delivery are not verified. |
| 26 | Remote config/offline behavior | WIRED IN CODE (not run) | Remote-config and cache contracts exist; live publication, cache reconciliation and offline recovery are unverified. |

### Confirmed boundaries and unresolved runtime checks
- The Android app's configured API base is the Cloudflare Worker. The legacy `functions/` directory must not be assumed to match Worker behavior or to be the active Android backend.
- Worker and legacy Functions route parity has not been exhaustively executed or verified against a live deployment. Treat Worker routes as the implementation source for this app and test each app-called route's request/response contract.
- Recent commits improve image-intent routing, image history/display handling, credit refresh, camera ownership/lifecycle, front/back lens errors, flashlight controls, screen consent, chat loading/action contrast, and sanitized admin diagnostics. These changes are source evidence only, not runtime PASS.
- Camera and microphone access must follow Android permission and foreground-service rules. Screen capture must use Android's explicit MediaProjection consent and stop on revocation. The app must not attempt to take over TikTok/YouTube's camera or bypass platform permissions.
- Provider secrets, Play Console access, Firebase/Storage rules, deployed Worker secrets, real purchase credentials, and a physical Android test run were not available to validate in this audit.

### Phase 1 outcome
The current source feature map and the major known gaps are now documented. **Phase 1 source-audit deliverable is complete; end-to-end runtime verification remains pending.** Phase 2 is branding/logo consistency: audit all shared logo placements, preserve the existing approved logo, and fix only confirmed inconsistencies without redesigning screens.
