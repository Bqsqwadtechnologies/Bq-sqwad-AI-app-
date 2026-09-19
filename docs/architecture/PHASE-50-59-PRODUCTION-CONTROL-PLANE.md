# BQ Sqwad AI — Phases 50–59 production control plane

This document defines the production boundary for the advanced phases without treating a capability as complete merely because a UI control exists.

## Phase 50 — Codron Advanced Agent System
Agent records, bounded tool permissions, approval checkpoints, knowledge references and activity records use server-owned state. Agent execution must pass the canonical adminAdvancedOptions gate.

## Phase 51 — AI Research & Intelligence
Research is a separate capability from normal chat. Search, source verification, research depth and credit cost are controlled by canonical capability records.

## Phase 52 — Workflow & Automation
Workflows are versioned records with explicit steps. Scheduled/background execution is disabled unless the corresponding admin controls permit it. Destructive actions require an approval checkpoint.

## Phase 53 — Business AI Employees
Business agents are tenant-owned records with purpose, instructions, tools, channels, knowledge references and plan entitlement. No agent receives unrestricted device or account permissions.

## Phase 54 — Files & Knowledge
File operations are represented by references rather than raw secrets. Large uploads, resumable transfers, temporary vault retention and semantic search are controlled by admin limits.

## Phase 55 — Advanced Generation
Image, video and file-analysis capabilities remain separate entitlement-gated capabilities. Provider routing and credit pricing come from the backend; the APK must not hardcode provider secrets or production prices.

## Phase 56 — Evaluation & Provider Intelligence
Evaluation runs, provider health, fallback policy, incident replay and version rollback are represented as auditable server records. Evaluation data must not silently change production routing.

## Phase 57 — Security & Privacy
Sensitive operations require authenticated requests, least-privilege capability gates, audit records and explicit device consent. Data export/deletion and retention policy remain server-authoritative.

## Phase 58 — Platform & Plugins
Plugins are descriptors with requested permissions and explicit admin approval. A plugin cannot execute merely because it is installed or listed.

## Phase 59 — Sync, Accessibility & Globalization
Cross-device state is conflict-aware and server-owned. Accessibility, language and voice preferences are user settings, not hardcoded assumptions.

## Production gate
A phase is only considered operational after:
1. source implementation exists,
2. backend tests pass,
3. Android compilation passes,
4. relevant runtime flows are exercised,
5. signed APK/AAB verification succeeds,
6. no automatic workflow is enabled.

Both repository CI workflows remain manual (workflow_dispatch) by design.
