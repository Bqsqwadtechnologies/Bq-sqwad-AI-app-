# BQ Sqwad AI — Advanced Capability Expansion

## Subscription-gated capabilities

The following are subscription-only in the product policy:

- Image generation
- Video generation
- File/document analysis

The native app must never decide subscription status from a local flag. It asks the server for the current entitlement and the server performs the final authorization, pricing and credit checks.

## Added intelligence layers

### AI orchestration
- task classification
- provider/model routing
- web-search selection
- tool selection
- multimodal routing
- retries, timeout and cancellation
- provider health and failover
- cost/credit estimation

### Multimodal
- images, OCR, screenshots and charts
- multi-image comparison
- documents and structured files
- audio understanding
- video understanding
- multi-file reasoning
- citations and source references

### Memory
- conversation context
- user-approved personal memory
- project memory
- business memory
- temporary task context
- inspect/export/clear controls

### Device intelligence
- intents and deep links
- calendar/reminders
- location assistance
- sharing
- contacts
- camera/microphone
- screen awareness with explicit Android consent
- media controls

### Research
- multi-source web research
- source cards/citations
- saved research
- research export
- follow-up research

### Voice and camera
- barge-in
- voice activity detection
- low-latency turn taking
- OCR
- scene/object understanding
- privacy controls
- session controls

### Learning
- personalized lessons
- quizzes
- revision
- document-to-course
- voice teaching
- progress tracking

### Business
- CRM
- leads
- bookings
- workflows
- automation
- knowledge
- agents
- channels
- analytics
- reports
- permissions
- enterprise/custom configuration

### Agent/Codron
- knowledge ingestion
- multimodal references
- tools and permissions
- evaluations
- versioning
- rollback
- deployment states
- analytics

### Reliability and safety
- rate limits
- abuse protection
- idempotency
- credit reservation/reversal
- resumable uploads
- retry queues
- incident IDs
- admin audit logs
- provider emergency controls
- secure file pipeline

## User disclosure requirement

When a newly added capability is subscription-only, the UI should clearly tell the user before an action is attempted:

**Subscription required**
This capability is available on a paid subscription. View plans to continue.

For expensive operations, also show the expected credit usage when the server can calculate it.

## Never claim operational status prematurely

A capability is only marked operational after:
1. native UI exists;
2. backend contract exists;
3. authentication/authorization is enforced server-side;
4. provider integration exists;
5. entitlement and credit checks work;
6. failure paths are handled;
7. QA passes on a real Android build/device.

Gradle/APK work remains deferred until Phase 50.
