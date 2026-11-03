# Missing feature prices — direct credit-rule audit

The website **Admin Live Credit Pricing** dashboard is the canonical editor for model-specific token rates and fixed or unit-priced feature rules. Customer charges are final credit values; no provider USD conversion, percentage markup, fee, or model multiplier is applied.

## Shared generation rules

The Cloudflare Worker and website read the same Firestore `generationCostRules` collection. Known operations include:

| Feature | Operation |
| --- | --- |
| Image generation | `image.generate` |
| Image editing | `image.edit` |
| Background upload | `chat.background_upload` |
| Team member message | `team.member_message` |
| PDF generation | `file.pdf` |
| Video generation | `video.generate` |
| Tavily search | `web.search` |
| Deep research | `web.research` |
| Live voice | `live.voice` |
| Live camera/screen | `live.camera` |
| Transcription | `speech.transcribe` |

Other fixed-price capabilities use `feature.<capability>`. Legacy `adminAdvancedOptions/capability_*` prices are migrated into `generationCostRules` without replacing an existing rule. Default image/background/team-message prices are seeded only when neither a shared rule nor a valid legacy value exists.

## Production verification

The admin-only `GET /admin/pricing/audit` endpoint checks required shared generation rules and reports missing or invalid entries in `adminAlerts`. A repository source change does not prove that production Firestore contains every rule or that an external provider request succeeds. Verify the authenticated audit response and a successful request before treating every feature as live.
