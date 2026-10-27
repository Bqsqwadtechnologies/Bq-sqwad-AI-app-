# Wire new chat modules into BQSqwadApp.kt

## Files added
- `ui/chat/TeamChooserScreen.kt` — one-time team picker + Settings change row
- `ui/chat/AiReplyActions.kt` — logo action bar + AiActivityCard (mid-task photos)
- `ui/chat/MainChatHelpers.kt` — web-search heuristic + credit format/exhaust

## Required call sites in BQSqwadApp.kt

1. After successful sign-in / first shell open:
```kotlin
if (needsTeamChooser(context)) {
  // show TeamChooserScreen full-screen
  // onTeamChosen { saveTeamId(context, it.id); dismiss }
}
```

2. Settings:
```kotlin
TeamSettingsRow(currentTeamId = savedTeamId(context), onChangeTeam = { showTeamChooser = true })
```

3. Replace needsCurrentWebSearch body with:
```kotlin
MainChatHelpers.needsCurrentWebSearch(text)
```

4. Under AI replies use:
```kotlin
AiReplyActionBar(defaultReplyActions(onCopy=..., onShare=..., onSpeak=..., onRegenerate=...))
```

5. While streaming / tool use:
```kotlin
AiActivityCard(title = "Checking the web…", detail = query, photo = { /* optional ImageBitmap */ })
```

6. Hide numeric credits in main chat status; only amber "Usage limit reached" when exhausted.

Local BQSqwadApp.kt already contains items 3 and 6 + compact sources dropdown.
