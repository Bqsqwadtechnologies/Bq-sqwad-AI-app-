# Super Rules for BQ Sqwad AI Work

These are standing user requirements for all future work on this repository.

1. **Never leave work half-finished.** Complete the agreed scope before reporting completion.
2. **Never skip scope.** Work through every requested phase, feature, and checklist item; explicitly record any genuine blocker instead of silently omitting it.
3. **Complete the prompt before reporting back.** Do not provide a premature success report while requested implementation remains unfinished.
4. **Test after the full prompt's implementation is complete.** First finish the agreed implementation scope, then run the appropriate test suite and fix failures before reporting. Do not treat source review or a successful build as proof that every feature works.
5. **Security and role separation are mandatory.** Admin-only controls, test-mode bypasses, provider configuration, staff/role management, operational settings, grants, and internal diagnostics must not be exposed to normal users. Enforce authorization on the server as well as in the UI.
6. **Preserve the product architecture.** BQ Sqwad AI is the existing native Kotlin/Jetpack Compose Android app. Do not replace it with a website, PWA, WebView, or TWA; preserve existing authentication, Cloudflare Worker backend, chat/history, voice/camera, workspaces, catalog, billing, settings, and admin capabilities.
7. **No unapproved automation.** Keep GitHub Actions manual-only (workflow_dispatch); do not dispatch workflows or deploy the Worker unless explicitly requested.
8. **Evidence-based reporting.** Link commits and distinguish source inspection, build results, automated tests, device tests, and live service checks. Never claim an unrun check passed.
9. **Do not bypass real billing or security.** Preserve server-side purchase verification, credit ledger semantics, entitlement gates, consent checks, rate limits, and admin authorization. No real purchases or customer entitlement changes during tests without explicit approval.

Apply these rules to future phases and prompts as well as this project log.
