# Missing feature prices — release verification required

This report is intentionally conservative. Repository access does not expose the live Firebase/Firestore contents of `adminAdvancedOptions`, so I cannot truthfully identify which price documents are absent in production. No price has been invented for the unknown entries below.

## Seeded by the current Worker migration

These defaults are written only when the corresponding document is absent. Existing administrator values are preserved.

| Feature document | Seeded credit cost |
| --- | ---: |
| `adminAdvancedOptions/capability_image_generation` | 1.5 |
| `adminAdvancedOptions/capability_image_edit` | 1.5 |
| `adminAdvancedOptions/capability_chat_background_upload` | 2 |

## Fixed-output feature documents requiring a live-store check

The following features are referenced by current Worker or website generation paths but are not included in the three-document seed migration. Check whether each document exists and contains a finite, non-negative `creditCost`. If absent, the feature should fail closed and write `adminAlerts/missingPrice_<feature>`.

- `adminAdvancedOptions/capability_pdf` — PDF generation
- `adminAdvancedOptions/capability_video_generation` — video generation
- `adminAdvancedOptions/capability_music` — music generation
- `adminAdvancedOptions/capability_resume` — resume generation
- `adminAdvancedOptions/capability_file_generation_<format>` — non-PDF file generation, for each enabled format
- `adminAdvancedOptions/capability_web_search` — search operation fee, in addition to token-billed answer synthesis
- `adminAdvancedOptions/capability_deep_research` — research operation fee, in addition to token-billed answer synthesis

These are **verification candidates, not confirmed missing records**. The actual missing list can only be finalized after querying the live admin store with authorized Firebase access. Do not fill any of these with guessed prices.
