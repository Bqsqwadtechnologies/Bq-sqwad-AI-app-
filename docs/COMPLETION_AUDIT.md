# COMPLETION_AUDIT (app + Worker mirror)

See website repo `docs/COMPLETION_AUDIT.md` for the full table.

## This pass (Prompt I)

- Worker `providers.js`: default remains `gpt-6-luna`; allowlist now includes `gpt-5.6-luna` so BQ-Codron can run when the user selects it (not as silent default).
- Plan sync: website adds `GET /api/native/entitlements` and `me.plans` — app must call these on launch/resume (WorkspaceAccessGate follow-up).

## Still open on app

- Wire ModelSelector + PlusAttachmentSheet into main chat composer
- Onboarding launch gate + profile write
- Secret chat UI / biometrics
- MessagingStyle notifications with full-color logo Person
- Consume multi-plan entitlements API
