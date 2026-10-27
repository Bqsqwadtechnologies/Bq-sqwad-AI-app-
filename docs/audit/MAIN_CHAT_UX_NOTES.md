# Main chat UX changes (prepared locally in BQSqwadApp.kt)

## Applied in local working tree (need large-file push to main)

1. **Credits hidden in main chat** — numeric "Credits: x.xx" no longer shown in the main chat status row. Only shows amber "Usage limit reached" when balance is exhausted.
2. **Exhaustion rule** — `creditsExhausted` ignores in-flight requests (`&& !busy`).
3. **Web search heuristic expanded** — triggers on 2024–2029 years, "who is / what is / look up / search / on the web", live scores, prices, news, etc., so answers are not stuck in 2024 knowledge.
4. **Web sources compact dropdown** — sources collapse under "Web sources (N)" with Language icon; expand to show chips.
5. **Upload button** — already inside the composer surface with blue tint `0xFF1457FF`.

## Still open (user requests)

- Team selection onboarding with previews + change later in Settings
- AI displaying photos of what it is doing mid-task (vision/tool activity cards)
- Professional logo chips for every AI-reply action feature
- Full Chat Folders + Journey UI (handoff already written)
- Push remaining index.js billing hold/settle patches

## How to land BQSqwadApp.kt

The file is ~500 KB. Prefer committing from a local clone with authenticated git, or split the MainChatScreen into its own file in a follow-up PR.
