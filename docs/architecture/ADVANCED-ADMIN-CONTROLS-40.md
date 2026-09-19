# Advanced Admin Controls — 40 Options

All 40 options are represented in `functions/advanced-admin.js`, persisted in Firestore collection `adminAdvancedOptions`, and exposed only through authenticated admin endpoints.

Admin API:
- GET `/admin/advanced-options`
- POST `/admin/advanced-option`

Every option has these server-side controls:
- enabled
- rollout percentage
- emergency kill switch
- human approval requirement
- credit cost
- daily limit
- concurrency limit
- provider capability/provider/model override
- allowed plan IDs
- version
- schedule
- alert threshold
- updated-by audit field

## 40 controls

1. AI cost optimizer
2. Provider health monitor
3. Model version management
4. Evaluation laboratory
5. Prompt/config versioning
6. Capability-level credit pricing
7. Search controls and caching
8. Device approval policies
9. AI activity timeline
10. Action preview
11. Human approval checkpoints
12. Undoable AI actions
13. Agent sandbox
14. Plugin marketplace and permissions
15. Background jobs and queues
16. Scheduled AI tasks
17. Monitoring agents
18. Usage forecasting
19. Automatic quality fallback
20. Emergency kill switches
21. AI incident replay
22. Security monitoring
23. AI abuse/risk detection
24. Configuration snapshots and rollback
25. Regional pricing/promotions
26. Subscription lifecycle controls
27. Business AI employees
28. Agent-to-agent workflows
29. Cross-device conversation/project sync
30. Secure temporary file vault
31. Large-file resumable uploads
32. Multilingual UI and voice
33. Accessibility-first AI controls
34. Passkeys/session/device management
35. Data export and account deletion
36. Provider/model route override
37. Feature rollout percentages
38. Incident controls and replay
39. Admin-controlled capability marketplace
40. Data retention and deletion policy controls

The Android Admin Control Center reads and updates this catalog through the authenticated Firebase ID-token API. Provider credentials remain server-side; these controls never place provider secrets in the APK.

Build/CI remains untouched by this change, and no GitHub Actions workflow is dispatched automatically.
