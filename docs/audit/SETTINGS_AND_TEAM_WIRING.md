# Settings credits + Team wiring

## Credit balance in Settings (not main chat)

Use `SettingsCreditsCard` from `ui/chat/WorkspaceTeamScreen.kt` inside SettingsScreen under Payments & billing:

```kotlin
item {
  SettingsCreditsCard(
    balanceLabel = creditBalanceLabel, // load via WorkspaceApiClient().credits()
    unlimited = isAdminUnlimited,
    onOpenPlans = onOpenPlans
  )
}
item {
  FeatureCard("Team & workspace", "Invite by email, roles, and which account pays for usage.") {
    showTeamWorkspace = true
  }
}
```

Main chat should not show numeric credits (only amber "Usage limit reached" when exhausted).

## Team rules (implemented server-side in team-workspace-native.js)

- Invite **only** emails that already have a BQ account
- Roles: admin / moderator / member
- **Only admin** can invite, remove, request charge-account
- Charge-from another member requires **their approval** (in-app + email outbox)
- Notifications: `notifications/` + `emailOutbox/`

## API routes (must be registered in index.js)

Local index.js already contains these; **push index.js from a machine with git auth**:

- GET `/native/team/members`
- POST `/native/team/invite`
- POST `/native/team/remove`
- POST `/native/team/request-charge-account`
- POST `/native/team/approve-charge`
- POST `/native/team/accept-invite`

Module file is already on main: `cloudflare-worker/src/team-workspace-native.js`

## Terms & Privacy

App already opens website:
- https://bqsqwadai.com/terms
- https://bqsqwadai.com/privacy-policy

Team screen also links both.
