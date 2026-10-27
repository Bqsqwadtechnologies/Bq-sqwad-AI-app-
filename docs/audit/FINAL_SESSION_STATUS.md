# Final session status — BQ Sqwad AI

## On main (committed)

| Area | Status |
|------|--------|
| Manual-only workflows | DONE |
| billing-hold module + tests (5/5) | DONE |
| BrandCopy + infrastructure guard test | DONE |
| Team invite API + WorkspaceTeamScreen | DONE |
| SettingsCreditsCard component | DONE |
| BqChatHeader / Image experience / ThemeOncePicker | DONE |
| Training pipeline prepare-only module | DONE |
| Part 0 vision token billing | CONFIRMED in code |
| Product name / applicationId | com.bqsqwad.ai / BQ Sqwad AI |

## Local working tree (diff ready, push with git auth)

- BQSqwadApp.kt wired to new modules + signed-out gate + Settings credits
- index.js team + training routes + hold integration

## Cannot fully close without

1. `git push` of the two large files from an authenticated machine
2. Manual Worker DEPLOY
3. Website repo mirror (separate: bqsqwadai-b3064406)
4. Device tests (Play checkout, live camera/voice)
5. Full Folders/Journey Compose UI (handoff doc exists)

## Owner test list remaining

- 1 credit chat after Worker deploy with hold/settle
- Sign-out → only sign-in screen
- Settings shows credit balance
- Image gen shows rotating logo only
- Team invite existing account by email
