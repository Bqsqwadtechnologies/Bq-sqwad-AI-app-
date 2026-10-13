# Super Rules for BQ Sqwad AI Work

These are standing user requirements for all future work on this repository. The same rules are maintained at the repository root in `SUPER-RULES.md`.

1. **Never leave work half-finished.** Complete the agreed scope before reporting completion.
2. **Never skip scope.** Work through every requested phase, feature, and checklist item; explicitly record any genuine blocker instead of silently omitting it.
3. **Complete the prompt before reporting back.** Do not provide a premature success report while requested implementation remains unfinished.
4. **Test after the full prompt's implementation is complete.** First finish the agreed implementation scope, then run one consolidated verification pass and fix failures where tools and permissions allow. Do not treat source review or a successful build as proof that every feature works.
5. **Security and role separation are mandatory.** Admin-only controls, test-mode bypasses, provider/model configuration, staff/role management, operational settings, credit grants, and internal diagnostics must not be exposed to normal users. Enforce authorization on the server as well as in the UI. Organization owners' own organization-management controls are not global app-admin controls.
6. **Preserve the product architecture.** BQ Sqwad AI is the existing native Kotlin/Jetpack Compose Android app. Do not replace it with a website, PWA, WebView, or TWA; preserve existing authentication, Cloudflare Worker backend, chat/history, voice/camera, workspaces, catalog, billing, settings, and admin capabilities.
7. **Workflow ownership belongs exclusively to the user.** GitHub Actions must remain manual-only (`workflow_dispatch`). Only the user may manually start a workflow from GitHub. The assistant must NEVER dispatch, trigger, rerun, restart, cancel, schedule, or otherwise initiate any workflow, even if testing would be useful. Never add automatic triggers (`push`, `pull_request`, schedules, or similar), enable automation, or change workflow configuration without the user's explicit approval. Before reporting, state clearly that no workflow was run if none was run.
8. **Stay on the existing repository and main branch.** Do not create a replacement app or repository. Preserve the existing package/application ID and native Android architecture.
9. **Protect unrelated configuration.** Do not change Gradle files, Android manifest, app version, signing configuration, Google Services configuration, billing product/base-plan IDs, backend endpoint, or workflow files unless the user explicitly approves that exact change.
10. **Keep secrets secret.** Never commit, print, log, or request API-key values in chat. Use Cloudflare Worker secrets for server-side keys and never place them in the Android app. Secret-status checks may report presence, not the secret value or whether the provider accepts it.
11. **Preserve product behavior and billing security.** The BQ Sqwad AI catalog remains customer-facing; Google Play Billing is only for configured eligible checkout and server verification. Do not synthesize prices, locally grant entitlements, or weaken consent, authorization, rate limits, idempotency, or credit reserve/commit/reversal.
12. **Evidence-based reporting.** Link commits and distinguish source inspection, automated tests, builds, deployed backend checks, billing tests, and physical-device tests. Never claim an unrun check passed.
13. **Keep changes complete and traceable.** Map each approved requirement to affected files and verification evidence. Do not silently remove features or substitute placeholder behavior for working functionality.
14. **Report blockers honestly.** If work depends on unavailable credentials, external account access, a local checkout/build environment, a physical device, or a user-only manual action, complete the remaining safe work and identify the exact blocker.

## Phase discipline

- Complete each approved phase in scope before moving to the next phase.
- For each AI feature, inspect its Compose entry point, workspace, client/API contract, Worker route/provider, authentication/consent/entitlement/credit gates, errors/cancellation, history/persistence, and admin-only surfaces.
- Improve existing workspace organization, colors, spacing, settings, and accessibility using the established design system; do not redesign the product or change its identity.
- Keep normal-user experiences free of admin-only controls and internal implementation details.
- Run one consolidated verification pass only after all source work in the currently approved prompt is complete. Never trigger GitHub Actions as part of that pass.
- A phase may be source-complete while its runtime status remains **NOT RUN**. Do not conflate these statuses.
