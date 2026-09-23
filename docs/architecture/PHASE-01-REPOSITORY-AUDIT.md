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
