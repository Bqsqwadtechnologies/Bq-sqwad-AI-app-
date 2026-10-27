# Part 0 completion + redesign progress

## Part 0 checks

| Item | Status | Notes |
|------|--------|-------|
| vision() token billing | **OK** | estimateTokenReservation + settle + calculateTokenCost; allowFractional |
| chargeCredits floors | **PARTIAL** | allowFractional holds; model-registry floors removed on main |
| BrandCopy / storeNotice | **OK** | Present |
| enableAutoServiceReconnection | **OK** | PlayBillingClient |
| Customer Google Play strings | **MOSTLY OK** | BrandCopy scrubs; Legal/Admin excepted |
| Website same formula | **CANNOT VERIFY** | Shared users/{uid}; website separate repo |
| Worker / Gradle tests | **PARTIAL** | billing-hold 5/5; full suite needs CI |

## Parts 1–8

| Part | Status |
|------|--------|
| 1 Header module | BqChatHeader.kt on main |
| 2 Reply while streaming | Gated in local BQSqwadApp.kt |
| 3 Image gen logo-only | Local BQSqwadApp + BqImageExperience |
| 4 Media viewer | BqImageExperience.kt |
| 5 Theme once | ThemeOncePicker.kt |
| 6 Website sync | Shared authority; SettingsSyncClient |
| 7 Training pipeline | training-pipeline-native.js (flag off) |
| 8 Name | BQ Sqwad AI / com.bqsqwad.ai |

## Large files still local

BQSqwadApp.kt + index.js — push from authenticated git, then manual DEPLOY.
