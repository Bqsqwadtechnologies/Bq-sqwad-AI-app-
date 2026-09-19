# Phase 31 — Gemini/OpenAI Separation
## Objective
Keep primary AI and device-command interpretation logically separate.
## Rule
OpenAI is the primary model/provider path where configured. Gemini is reserved for the phone-command interpreter role where that capability is enabled.
## Safety
Device commands require explicit user intent, Android permissions, and a narrow allowlisted command contract.
## Acceptance
A normal chat request cannot accidentally become a device command merely because a model is available.