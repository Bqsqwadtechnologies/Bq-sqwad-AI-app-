# BQ Sqwad AI Cross-Platform Synchronization Contract

The canonical shared contract is maintained in the website repository at `docs/cross-platform-sync-contract.md`.

## Current Android integration
- Firebase Authentication UID is the account identity.
- Chat History now uses the authenticated website endpoint `https://bqsqwadai.com/api/native/chats`, which writes to the same `chats/{chatId}` records used by the website.
- Requests send a Firebase ID token in the Authorization header. The server derives the UID from the verified token; the Android client does not supply an ownership UID.
- Legacy Android chat history in Cloudflare Worker's `chatHistory` collection is imported by the website API on authenticated list requests. The migration is best-effort and non-destructive; the old copy is only removed on an explicit shared-chat deletion.
- The Cloudflare Worker remains authoritative for its existing AI, credit-reservation, billing verification and other protected operations. Do not move provider secrets or billing authority into the Android app.

## Known remaining synchronization work
- Saved memories/preferences still have different legacy shapes (`aiMemories` on the website versus `userMemory` in the Worker). They require a separate consent-aware migration/API implementation and tests.
- Account-level settings, entitlement projection, generated-content history and conflict/version metadata need further implementation and cross-platform runtime verification.
- No GitHub Actions, builds or releases were triggered.
