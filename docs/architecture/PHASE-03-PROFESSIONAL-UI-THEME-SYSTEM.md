# Phase 3 — Professional UI & Theme System
## Objective
Define a native-first visual system original to the new BQ Sqwad AI app. The old app is reference only; its screens, layout, components, styling, and implementation are not copied.
## Rules
- Android-native Compose surfaces only.
- Every theme keeps a visible logo slot; no logo-free theme.
- Themes change tokens, surfaces, typography emphasis, density, and motion—not product structure.
- User and Admin shells have distinct navigation and visual hierarchy.
- No fake controls: every visible action maps to a planned capability.
## Theme set
1. BQ Sqwad Core — premium dark workspace.
2. BQ Sqwad Contrast — stronger accessibility contrast.
3. BQ Sqwad Minimal — restrained surfaces and reduced visual noise.
## Component contract
Buttons, cards, tabs, sheets, dialogs, banners, inputs, empty states, loading states, error states, permission prompts, credit indicators, and workspace headers share tokenized dimensions and states.
## Acceptance
- New logo can be inserted without redesigning screens.
- All major workspaces have consistent navigation and states.
- Theme switching never changes permissions, entitlements, providers, pricing, or business logic.