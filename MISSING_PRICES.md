# BQ Sqwad AI — Final credit-price defaults

These are launch defaults stored in the shared Firestore collections. All listed values are BQ credits, not currency. The website Admin Live Credit Pricing dashboard remains authoritative after the one-time seed marker is applied.

## Token prices — credits per one token

| Model | Meter | Credits per token |
|---|---|---:|
| `gpt-6-luna` | input / cached input / output | 0.000005833333333333333 / 0.0000005833333333333333 / 0.000029166666666666667 |
| `gpt-5.6-luna` | input / cached input / output | 0.000011666666666666668 / 0.0000011666666666666668 / 0.00007 |
| `gpt-image-2.5-sunburst` | text input / text cached | 0.0002916666666666667 / 0.00007291666666666667 |
| `gpt-image-2.5-sunburst` | image input / cached image input / image output | 0.00046666666666666666 / 0.00011666666666666667 / 0.00175 |
| `gpt-realtime-2.1-mini` | text input / cached / output | 0.000035 / 0.0000035 / 0.00014 |
| `gpt-realtime-2.1-mini` | audio input / cached / output | 0.0005833333333333334 / 0.0000175 / 0.0011666666666666668 |
| `gpt-realtime-2.1-mini` | image input / cached | 0.00004666666666666667 / 0.000004666666666666667 |
| `gpt-image-1-mini` | text input / cached / output | 0.00011666666666666667 / 0.000011666666666666668 / 0.00046666666666666666 |
| `gpt-image-1-mini` | image input / cached / output | 0.00014583333333333335 / 0.000014583333333333333 / 0.00046666666666666666 |
| `gpt-4o-mini-transcribe` | audio input / text output | 0.00007291666666666667 / 0.0002916666666666667 |
| `gpt-4o-mini-tts` | text input / audio output | 0.000035 / 0.0007 |
| `text-embedding-3-small` | input | 0.0000011666666666666668 |
| `omni-moderation-latest` | input | 0.00001 |

## Duration and fixed generation prices

| Operation | Feature | Credits | Unit |
|---|---|---:|---|
| `live.voice` | Live Voice | 0.045 | second |
| `live.camera` | Live Camera and normal Share Screen | 0.09 | second |
| `speech.transcribe` | Dictation and Smart-session transcription | 0.0204337 | second |
| `speech.synthesize` | Text to speech | 0.0145833 | second |
| `file.pdf` | PDF | 0.3 | page |
| `file.docx` | Word | 0.3 | page |
| `file.xlsx` | Excel | 0.45 | page |
| `file.csv` | CSV | 0.15 | file |
| `file.txt` | Text | 0.15 | file |
| `file.md` | Markdown | 0.15 | file |
| `file.html` | HTML | 0.15 | file |
| `file.json` | JSON | 0.15 | file |
| `file.code` | Code file | 0.15 | file |
| `file.xml` | XML | 0.15 | file |
| `file.yaml` | YAML | 0.15 | file |
| `web.search` | Web search | 0.2 | returned page |
| `web.research` | Deep research | 7 | request |
| `chat.background_upload` | Chat background upload | 2 | upload |
| `team.member_message` | Team member message | 0 | message |
| `feature.connector_action` | Connector action | 0.4 | call |
| `resume.generate` | Resume | 0.75 | resume |
| `video.generate` | Video | 87.573 | second |
| `music.generate` | Music | 1.5 | second |
| `music.render` | Music render | 0.3 | second |

## Image generation and editing

Image generation and editing remain token-metered. The charge is measured token usage multiplied by the applicable text, cached-text, image, cached-image and output-image rates above. Size and quality do not add a separate flat fee. The quality/size amounts supplied in the launch prompt are estimates for display and test comparison only; they are not stored prices.

## Smart-session calculation

Smart Practice and Smart Share Screen add the transcription rate for the same elapsed seconds:
- Smart Voice: 0.045 + 0.0204337 = 0.0654337 credits/second.
- Smart Camera/Share Screen: 0.09 + 0.0204337 = 0.1104337 credits/second.
- A 30-second Smart Voice heartbeat costs 1.963011 credits before final rounding.

## Source-level unpriced capabilities

The Worker maps these capabilities to `feature.*` operations but does not provide launch defaults for them. They remain **UNPRICED** until an administrator supplies a BQ credit rule or a live Firestore audit confirms an existing configured rule:
- `feature.business`
- `feature.business_ai`
- `feature.companion`
- `feature.companion_checkin`
- `feature.device_commands`
- `feature.learning`
- `feature.organization_studio`
- `feature.truth_guard`
- `business_plan_builder` (legacy Firebase Functions entry point has no launch rule)
- Legacy Firebase Functions `image_generation` and `image_edit` entry points (token usage is not returned by that retired path; token-metered generation must use the Worker)
- Legacy Firebase Functions `youtube_live`, `truth_guard`, `companion`, `companion_checkin`, `learning_studio`, `organization_studio`, and `device_commands` paths until each has a corresponding active `generationCostRules` operation

The legacy Firebase Functions billing adapter now reads `generationCostRules` for supported fixed operations and fails closed when a matching rule or a required token/duration metering path is unavailable. It does not fall back to a capability-document price. These source-level UNPRICED entries are not guessed or assigned a default. This is a source-default audit, not proof that these rules are absent from production Firestore.

## Seeding and verification

- The generation seed marker is `adminAdvancedOptions/bq_live_credit_pricing_defaults_v5`, version 5.
- All nine model token defaults use the shared marker `adminAdvancedOptions/bq_token_credit_pricing_defaults_v5` (version 5), shared by the Worker and website.
- The one-time cleanup removes legacy `UsdPerMillion` and `UsdPerToken` suffix fields from `tokenPricingRules` and `generationCostRules`. A separate version-two cleanup removes legacy price, conversion and multiplier settings from `config/aiModels`; that document now carries request-size limits only.
- Existing image-generation, background-upload and team-message prices are preserved by the generation seed.
- Later website dashboard edits are protected by the version-5 marker.
- `GET /admin/pricing/audit` must be checked with an authorized admin session after deployment. The Android Live Voice and Live Camera labels read `WorkspaceApiClient.livePricing()` from the public `/pricing` endpoint, which resolves the same `generationCostRules` as the Worker. This source file does not certify live Firestore values, deployment success, or measured image-generation charges.
