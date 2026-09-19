# Phase 32 — Push Notifications
## Objective
Add a proper notification architecture.
## Notification domains
- Account/security.
- Payment.
- Credit/entitlement.
- Business events.
- Agent events.
- Learning reminders.
- System notices.
- Admin broadcasts where authorized.
## Controls
User notification preferences are independent of admin global enablement. Sensitive content should not be unnecessarily exposed in notification previews.
## Acceptance
Notifications have deep-link destinations and safe fallback screens when the target feature is disabled.