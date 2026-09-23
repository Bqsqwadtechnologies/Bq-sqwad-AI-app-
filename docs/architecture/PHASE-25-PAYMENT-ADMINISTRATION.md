# Phase 25 — Payment Administration
## Objective
Give Admin Control Center provider management without exposing secrets to users.
## Admin operations
- Select active payment provider.
- Enter/update provider credentials through a secure server-side secret mechanism.
- Activate/deactivate provider.
- Configure supported products.
- View payment health.
- Review failed/pending transactions.
## Secret rule
API keys/secrets must be stored in a server-side secret manager or equivalent secure backend configuration—not in the Android app.
## Acceptance
The client receives provider status, never provider secret values.