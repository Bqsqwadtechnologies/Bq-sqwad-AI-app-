# BQ Sqwad AI — Phase 2 Native Architecture & Design System

## Purpose

Define the native Kotlin architecture and visual system before feature implementation.

This phase does not modify Gradle, build.gradle, settings.gradle, gradle.properties, MainActivity, CI, workflows, signing, or APK configuration.

## Product navigation principle

Every major AI capability owns its own workspace.

Never route these features back to Main Chat:

- Web Search
- AI Live Camera
- AI Live Voice
- Personal Intelligence
- Life Matters
- Learning Intelligence
- Business Intelligence
- Admin Control Center
- BQ Sqwad Codron plugin

Main Chat is a separate destination.

## User application shell

The user shell contains:

- Home
- Main Chat
- dedicated AI feature entry points
- Business
- Learning
- Personal Intelligence
- Life Matters
- credits/subscription
- notifications
- settings

The exact navigation layout will be selected during UI implementation, but the navigation model must preserve separate workspaces.

## Admin shell

Admin is a separate application surface guarded by server-side authorization.

Admin navigation:

- Overview
- Users
- Plans
- Credits
- Payments
- Providers
- AI configuration
- Feature configuration
- Business configuration
- Learning configuration
- Notifications
- Referrals
- Privacy
- Training
- BQ Sqwad AI Agent
- Plugins
- Incidents
- Audit

A normal user must never receive the admin navigation model.

## Design language

Target visual character:

- premium
- modern
- clean
- fast
- restrained
- high contrast
- excellent typography
- clear hierarchy
- purposeful motion
- consistent iconography
- strong dark-mode experience
- strong light-mode experience

Avoid:

- overcrowded dashboards
- giant text blocks
- unnecessary gradients
- excessive cards
- duplicate controls
- fake buttons
- placeholder AI features
- screens that visually imply unsupported capabilities
- routing separate features back to Main Chat

## Theme architecture

The app supports multiple complete themes.

### Theme 1 — BQ Sqwad Core

Primary branded theme.

Supports:

- dark
- light
- new BQ Sqwad logo

### Theme 2 — BQ Sqwad Contrast

A different visual treatment using the same functional design system.

Supports:

- dark
- light
- new logo

### Theme 3 — BQ Sqwad Minimal

A third visual identity with a quieter layout.

The logo remains available.

No theme is logo-free.

### Future themes

The architecture must permit additional themes without rewriting feature screens.

## Theme tokens

Centralize:

- colors
- typography
- spacing
- corner radii
- elevation
- borders
- icon sizes
- component heights
- animation durations
- accessibility contrast rules

Feature screens consume tokens instead of defining unrelated visual values.

## Typography

Use a clear hierarchy:

- display
- screen title
- section title
- body
- secondary body
- caption
- metadata
- button
- input

Business and payment screens receive special typography review because pricing readability is critical.

## Component system

Shared components include:

- top app bar
- navigation
- bottom navigation where appropriate
- feature header
- cards
- buttons
- icon buttons
- chips
- tabs
- segmented controls
- dialogs
- bottom sheets
- text fields
- search fields
- pricing cards
- credit balance card
- plan card
- usage meter
- agent card
- provider card
- status badge
- loading skeleton
- empty state
- error state
- confirmation state
- notification item
- permission explanation card

## Feature workspace template

Each major feature receives:

1. feature header
2. primary workspace
3. contextual controls
4. usage/credit visibility where relevant
5. feature settings
6. history where relevant
7. help/privacy entry
8. clear exit/navigation

The workspace must not visually impersonate Main Chat.

## Main Chat design

Main Chat contains:

- conversation area
- composer
- attachment control
- voice input
- image generation control
- video mode control
- Web Search control
- send
- response actions

Response actions are compact icon controls:

- speak
- copy
- share
- resend
- report

Controls should be visually quiet and appear in the correct context rather than becoming a large toolbar.

## Web Search design

Dedicated search workspace:

- search field
- search action
- result list
- source information
- query refinement
- search history
- loading state
- no-result state
- error state

It must not simply open Main Chat with a “Web Search” title.

## Live Camera design

Dedicated camera workspace:

- live camera surface
- AI observation area
- conversation/control layer
- microphone state
- AI speech state
- camera quality
- permission status
- screen-awareness control
- location control
- voice settings
- stop/end control

Screen awareness must clearly show when active.

## Live Voice design

Dedicated voice workspace:

- voice visualizer
- active speaker indicator
- conversation state
- microphone control
- AI mute control
- voice selection
- speaker/output
- interruption state
- end conversation

Do not present it as a text chat with a microphone attached.

## Learning design

Dedicated Learning Studio:

- current subject
- topic
- lesson
- teacher interaction
- progress
- voice teaching
- learning plan
- custom subject creation

## Life Matters design

Dedicated workspace.

Do not reuse Main Chat as the visible screen.

Provide:

- feature overview
- task/planning surface
- contextual tools
- history
- relevant AI controls
- usage/credit information

## Business design

Business home is a dashboard.

Top-level areas:

- Overview
- Connections
- Agents
- Knowledge
- Workflows
- Intelligence
- Analytics
- Settings

Plans and feature availability are displayed from live entitlement data.

Enterprise is represented as custom configuration, not as a standard self-service card.

## Payment design

Purchase surfaces must be polished.

Credit package card should clearly show:

- package name
- amount of credits
- price
- billing type if recurring
- benefits
- current selection
- payment method
- confirmation

Avoid dense paragraphs.

## Admin design

Admin uses a professional operations dashboard.

Every configuration page must distinguish:

- current value
- editable value
- save/apply state
- validation
- live status
- audit information
- rollback/history where appropriate

Provider cards should show:

- provider name
- enabled/disabled
- supported capabilities
- environment
- configuration status
- last update
- test connection action

Secret values must never be rendered after storage.

## Live configuration UX

When admin changes a supported remote configuration:

- save
- validate
- publish
- show active state
- update remote configuration version
- user clients refresh configuration
- cached clients reconcile safely

The app must not require an APK rebuild for ordinary catalogue/configuration changes.

## Accessibility

Design for:

- scalable text
- adequate touch targets
- screen readers
- high contrast
- clear focus states
- non-color-only status
- reduced motion where supported

## Design quality gate

A feature is not considered designed merely because a screen exists.

Each screen must have:

- a real purpose
- real navigation
- real loading state
- real error state
- real empty state
- correct entitlement state
- correct privacy state
- correct settings
- no fake controls
- no accidental Main Chat fallback

## Native boundary

This architecture is native Android.

Website integration is used for designated web-owned pages such as the privacy center, not as a replacement for native feature screens.

## Implementation boundary

No build-system files are introduced or altered in this phase.
