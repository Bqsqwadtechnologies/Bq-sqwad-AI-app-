# BUSINESS_AI_RELEASE_REPORT (app)

## On main
- `BusinessPlaybookClient` → website `/api/native/business-playbooks`
- `BusinessPlaybookScreen` — connect mock bank, deactivate, run three playbooks
- Worker contract test: `cloudflare-worker/test/business-playbook-contract.test.js`

## Tests run here
```
node --test business-playbook-contract.test.js
# 2 pass, 0 fail
```

Wire `BusinessPlaybookScreen` into Business workspace navigation in `BQSqwadApp` when opening Business (component is on main).
