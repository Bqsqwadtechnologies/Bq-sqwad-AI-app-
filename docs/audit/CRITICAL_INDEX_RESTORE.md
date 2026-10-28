# CRITICAL: restore cloudflare-worker/src/index.js on main

A failed large-file update temporarily replaced `index.js` with a stub.

## Fastest restore (GitHub Actions)

1. Open: https://github.com/Bqsqwadtechnologies/Bq-sqwad-AI-app-/actions/workflows/restore-index-js.yml
2. Click **Run workflow** on branch **main**
3. Wait until green — it restores from commit `d23a1fb75f7b51181d77c4674ad46d1dd48b925c` and wires:
   - `billing-hold` (computeHold)
   - `/native/team/*`
   - `/admin/training-candidates*`

## Manual restore (Termux / PC)

```bash
cd ~/Bq-sqwad-AI-app-
git checkout main
git pull origin main
git show d23a1fb75f7b51181d77c4674ad46d1dd48b925c:cloudflare-worker/src/index.js > cloudflare-worker/src/index.js
# optional: re-apply wiring from the restore workflow script
git add cloudflare-worker/src/index.js
git commit -m "RESTORE index.js from last good main"
git push origin main
```

Do **not** deploy the Worker until index.js is restored (file should be ~380KB+, not a few hundred bytes).
