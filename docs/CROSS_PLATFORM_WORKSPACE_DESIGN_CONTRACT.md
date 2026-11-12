# BQ SQWAD AI — Cross-platform workspace design contract

## Product rules

- Main Chat is the primary entry point for general AI tasks. Image generation and image editing are invoked from the Main Chat composer/tool menu and their results stay in the conversation. There is no standalone Image Generation workspace.
- Android and website use the same workspace names, capability labels, plan IDs, feature identifiers, and access states. Visual layouts may adapt to each platform, but the product model must not diverge.
- Firebase-backed plan catalog and verified entitlements are authoritative. A workspace may display a feature as available only when its catalog/entitlement state and any required provider connection permit it. Never infer paid access from a client-side plan label.
- Every visible action must have a real destination or operation, a loading state, a success state, and a recoverable error state. If an operation is not implemented or a provider is not connected, show a clear unavailable/connection state instead of a dead button.
- Do not describe provider-dependent or deferred features as active. The UI must distinguish available, plan-locked, connection-required, and not-yet-supported capabilities.
- Keep the existing BQ SQWAD visual identity: navy surfaces, blue/cyan accents, high contrast, clear typography, accessible touch targets, and responsive layouts.

## Workspace catalogue

| Workspace | Core capability areas |
|---|---|
| Main Chat | Streaming conversation, model selection, history, attachments, voice input, document workflows, integrated image generation/editing |
| AI Live Camera | Live camera preview, sampled-frame analysis, activity log, supported device actions |
| Live Voice AI | Voice sessions, interruption, microphone/output state, transcript |
| TruthGuard AI | Claim checks, verdict/confidence, cited evidence, source reliability, bias signals, plan-gated deep checks, history |
| Life Matters | Planning, goals, routines, tasks, practical next steps |
| AI Companion | Persona, conversation continuity, check-ins, preferences and memory controls |
| Learning Studio | Subjects, tutor, lessons, homework support, revision, quizzes and progress |
| Business AI | Assistants, knowledge, conversations, leads/CRM, inbox, channels, playbooks, analytics and tool connections |
| Organization AI / Studio | Organization profile, members/roles, shared knowledge, projects/channels, production sources/scenes, supported YouTube live workflow, X post-event publishing, schedules and production health |
| Frontier Lab | Research, experiments, prototypes, sourced briefs, market maps and feasibility reviews |
| Personal Intelligence | Projects, goals, notes and account-controlled memory |
| Data Analyst | Structured data analysis, calculations, charts and generated reports |
| My Tasks | Assigned work, deadlines and task status |

## Feature-state vocabulary

- **Available** — enabled by the verified plan and required integrations.
- **Plan required** — the feature exists, but the current account does not have the required entitlement.
- **Connect service** — the feature requires a supported provider/account connection.
- **Not supported yet** — the capability is not fully implemented end-to-end; do not present it as an active action.

## Known Organization AI boundaries

The current source audit does not verify physical multi-camera switching, live graphics compositing/lower-thirds, multi-destination simulcast, live viewer chat ingestion/moderation, donation overlays, viewer attendance analytics, or automated media highlight rendering. These must not be represented as active controls until the corresponding broadcaster/provider pipeline and tests exist.

## Release acceptance checklist

1. Main Chat remains the first/default workspace and contains the image-generation entry point.
2. No separate Image Generation workspace appears in navigation or workspace catalogs.
3. Workspace labels and feature identifiers match across Android and website.
4. Plan names, feature lists, limits, prices, and entitlement states come from the same server catalog.
5. The same Firebase account receives the same effective workspace access on both platforms.
6. Every enabled control performs its advertised operation; disabled controls explain why.
7. Keyboard, screen-reader labels, focus order, touch target sizes, responsive layouts, loading, empty, error, and success states are tested.
8. Automated checks pass, followed by real-device and provider sandbox verification. A source change alone is not proof of runtime or store-review approval.
