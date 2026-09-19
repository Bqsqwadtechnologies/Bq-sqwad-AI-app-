# Phase 27 — Admin Testing Mode
## Objective
Allow the administrator to test configured AI capabilities safely.
## Controls
- Global testing mode.
- Provider selection.
- Model selection.
- Feature enable/disable.
- Test prompts.
- Test media.
- Usage/credit behavior.
- Error simulation.
- Health checks.
## Guardrail
Testing mode must be isolated from normal user entitlements unless an explicit server-side test policy says otherwise.
## Acceptance
Admin can test provider routing without embedding provider credentials in the client.