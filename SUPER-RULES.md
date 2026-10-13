# BQ Sqwad AI — Super Rules

These rules govern every future change to this repository and are intended to be read before starting any task.

1. **Never stop halfway.** Finish the complete approved task before reporting it as complete.
2. **Never skip a requirement.** Trace every requested item to source changes and verification, or explicitly record why it cannot be completed.
3. **Finish the full prompt before reporting.** Do not call a phase or task complete while requested work remains.
4. **Test after the entire approved prompt is implemented.** Do not start a partial test cycle; after all requested source changes are complete, run one consolidated verification pass where tools and permissions allow.
5. **GitHub Actions are manual-only and user-controlled.** The assistant must never dispatch, rerun, cancel, or otherwise trigger any workflow. Only the repository owner may manually start a workflow.
6. **Do not change workflow files or workflow triggers** unless the user explicitly requests that exact change. Preserve the current manual-only workflow policy.
7. **Keep work on the existing `main` branch and existing native Android app.** Do not create a replacement app, website, PWA, WebView, TWA, or new repository.
8. **Protect unrelated configuration.** Do not change Gradle files, Android manifest, package/application ID, app version, signing configuration, Google Services configuration, billing product IDs, or backend endpoint unless the user explicitly approves that exact change.
9. **Preserve existing functionality and branding.** Maintain BQ Sqwad AI branding, the existing logo, navigation, theme behavior, authentication, chat/history, billing, and backend contracts while making targeted improvements.
10. **Keep administrative controls private.** Admin-only tools, diagnostics, provider/model management, secrets, internal testing controls, and operational controls must be hidden from ordinary users and protected by server-side authorization. Hiding UI alone is not authorization.
11. **Keep secrets secret.** Never commit, print, log, or request API-key values in chat. Use Cloudflare Worker secrets for server-side keys and never place them in the Android app.
12. **Do not claim unperformed verification.** Clearly distinguish source inspection, automated tests, builds, deployed-backend checks, and physical-device testing. Never claim a build, test, deployment, or feature works unless it was actually verified.
13. **Do not deploy or modify live infrastructure without explicit authorization.** Source changes do not prove that Cloudflare, Firebase, Google Play, or other live configuration has changed.
14. **Keep changes complete and traceable.** Map each approved requirement to the affected files and verification evidence; do not silently remove features or substitute placeholder behavior for working functionality.
15. **Report blockers honestly.** If a task depends on unavailable credentials, external account access, physical-device access, or a manual user action, complete the remaining work that is safely possible and identify the exact blocker.

## Phase discipline

- Complete each approved phase in scope before moving to the next.
- For each AI feature, inspect its user entry point, workspace, API client, Worker route/provider, authentication/consent/entitlement/credit gates, errors, history persistence, and admin-only surfaces.
- Organize existing workspaces professionally without redesigning the app or changing its identity.
- Keep normal-user experiences free of admin-only controls and internal implementation details.
- Run one consolidated verification pass only after all source work in the currently approved prompt is complete; never trigger GitHub Actions as part of that pass.
