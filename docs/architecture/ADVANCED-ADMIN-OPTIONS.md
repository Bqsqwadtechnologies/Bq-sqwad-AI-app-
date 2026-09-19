# Advanced Admin Control Center

## Canonical source of truth

The Admin Control Center uses **Firestore collection `adminAdvancedOptions` as the single authoritative configuration layer** for operational capability controls.

The retired `adminControls` and `adminProviderRoutes` stores are no longer read by the backend. Core capability routing, advanced controls, rollout, kill switches, provider/model selection and policy configuration are represented in the canonical control collection.

Provider secrets remain separate and encrypted server-side because secret material must never be placed in the client configuration.

## Dashboard sections

- AI & Models
- Providers
- Routing
- Capabilities
- Credits
- Pricing
- Plans
- Payments
- Subscriptions
- Business
- Agents
- Device Intelligence
- Privacy
- Security
- Users
- Notifications
- Referrals
- Codron
- Plugins
- Experiments
- Rollouts
- Monitoring
- Incidents
- Audit
- Data
- System
- Emergency Controls

## Core capability controls

The canonical control plane includes explicit routing controls for Main Chat, Device Commands, Web Search, Deep Research, Live Camera, Live Voice, Image Generation, Video Generation and File Analysis.

Each routing control can specify:
- enabled state
- rollout percentage
- kill switch
- primary provider
- primary model
- fallback providers
- approval requirement
- credit cost
- daily limit
- concurrency limit
- allowed plans
- schedule
- version
- alert threshold

## 40 advanced controls

The existing 40 advanced controls remain stable and are grouped into the dashboard sections above:

- provider cost optimizer
- provider health monitor
- model version management
- evaluation laboratory
- prompt/config versioning
- capability-level credit pricing
- search domain controls
- device approval policies
- AI activity timeline
- action preview
- human approval checkpoints
- undoable AI actions
- agent sandbox
- plugin marketplace
- background jobs
- scheduled tasks
- monitoring agents
- usage forecasting
- automatic quality fallback
- emergency kill switches
- incident replay
- security/risk monitoring
- configuration snapshots and rollback
- regional pricing/promotions
- subscription lifecycle
- business AI employees
- agent-to-agent workflows
- cross-device sync
- secure sensitive vault
- resumable uploads
- multilingual UI/voice
- accessibility controls
- passkeys/session/device management
- data export/deletion
- provider routing
- model routing
- provider secret management
- Web Search pricing
- feature rollouts
- Admin testing mode

## Editable operational policy

Every control can persist:
- enable/disable
- rollout percentage
- credit cost
- provider
- model
- daily limit
- concurrency limit
- allowed plan IDs
- schedule
- alert threshold
- configuration version

The shared policy object also supports response/context limits, tool-call limits, execution timeouts, rate limits, maintenance mode, background execution, confirmation/permission requirements, retention, fallback policy, latency budgets, error thresholds, queue limits, upload limits, notifications and audit requirements.

Web Search additionally supports normal-search, deep-research and result-page credit prices, maximum results and daily user limits.

## Security boundary

The Android app only receives control metadata. Provider credentials remain server-side. Admin API calls require verified Firebase ID tokens and the configured Admin account. Firestore Admin SDK operations execute in the privileged server environment. citeturn0search2turn0search8
