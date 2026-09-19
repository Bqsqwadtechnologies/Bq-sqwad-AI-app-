# Phase 10 — Live Voice AI Workspace
## Objective
Provide a dedicated real-time voice conversation experience.
## UX
- One conversation surface.
- Natural interruption/barge-in.
- Mic mute.
- AI speaking mute.
- Voice selection.
- Conversation history.
- Fast turn handling.
- Voice-chat integration.
## Exclusions
Do not expose separate tutorial, voice-to-text, or text-to-speech workspaces as the primary Live Voice experience.
## Failure behavior
Network, microphone, provider, quota, and interruption failures receive explicit states rather than silent hangs.
## Acceptance
The user can interrupt an AI response and continue the same conversation without starting a new chat.