# Codron, Subscription Entitlements, Reports and Admin Staff

Codron is inactive by default and is controlled by the authorized admin. The client never assumes Codron is active. Activation, deactivation, and training changes are server-controlled and auditable.

Image generation, video generation, and file analysis are subscription-gated. The client does not hard-code a plan name or entitlement. It reads the account's current server entitlement, including plan ID, status, dates, capabilities, limits, and renewal state. Video analysis is separate from video generation and can be enabled only for plans whose server entitlement includes it.

User reports are routed to an admin queue with category, status, reporter reference, timestamps, assignment and audit history. Sensitive reports are permission-scoped.

The admin can add authorized staff as Moderator, Collaborator, or Worker. Roles are permission bundles, not unrestricted admin access. Staff can be activated/deactivated and granted or revoked specific permissions.

The owner/admin controls Codron, providers, pricing, plans, capability rollout, staff permissions, reports, and security/audit settings. User and admin dashboards remain separate and privileged operations fail closed when authorization cannot be verified.
