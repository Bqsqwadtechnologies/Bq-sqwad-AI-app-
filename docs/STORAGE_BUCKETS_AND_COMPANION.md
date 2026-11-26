# BQ SQWAD AI Storage Buckets & AI Companion Architecture

## Overview
This document specifies the storage buckets, synchronization mechanisms, and ChatGPT 6 Luna token-saving cache for the BQ SQWAD AI ecosystem (Android App repository and Website repository `bqsqwadai-b3064406`).

## 1. Cloud Storage Buckets & Collections

### Primary Cloud Storage Bucket
- **Bucket ID**: `bqsqwadai.appspot.com`
- **Purpose**: Stores uploaded media, generated images, audio dictations, and companion profile assets.
- **Access**: Authorized Firebase users can upload and retrieve scoped media.
- **Guidance for other AI Features / Plans**: Other AI features and new plans can freely utilize this existing bucket. If separate dedicated storage buckets are provisioned in the future, services can configure additional bucket bindings cleanly without altering existing schemas.

### Firestore Collections (Shared between Android & Web)
1. `companionMessages`:
   - Contains all user and assistant messages for the Companion feature.
   - Fields:
     - `uid`: User ID (scoped and authenticated).
     - `role`: `"user"` or `"assistant"`.
     - `text`: Message body.
     - `assistantName`: Set to `"ChatGPT 6 Luna"`.
     - `modelId`: `"gpt-6-luna"`.
     - `cached`: Boolean indicating if retrieved from the token cache.
     - `createdAt`: Timestamp.
2. `companionProfiles`:
   - Contains `{ personality, memoryEnabled, updatedAt }`.
3. `companionSettings`:
   - Contains `{ notifications, saveHistory, safeMode, updatedAt }`.

## 2. Token-Saving Smart Cache
To avoid unnecessary credit consumption and token waste with OpenAI's ChatGPT 6 Luna:
- When a user submits a query to ChatGPT 6 Luna, the Cloudflare Worker normalizes the question and inspects prior `companionMessages`.
- If an exact match exists with a completed assistant response, the system immediately returns the answer.
- **Token / Billing Impact**: 0 credits are charged (`creditsCharged: 0`), and `cached: true` is returned.
- Both the Android Compose UI and TanStack Start Website render a badge:
  `⚡ Instant Cache · 0 Credits (Saved Tokens)`

## 3. Tool Guide & Plan Synchronization
- **ChatGPT 6 Luna Engine**: Direct conversational AI, personality alignment, and instant caching.
- **Daily Reflection Check-in**: One-click mindful check-ins logged directly to conversation history.
- **Memory Vault**: Retains context across sessions. Free tier displays an upgrade prompt; Basic, Plus, Premium, and Ultimate tiers enable toggleable memory.
- **Personality & Tone Tuning**: Supports Caring, Encouraging, Calm, Motivational, and Professional styles.
- **Voice & Speech Interface**: Direct links to Live Voice (`/voice`) and Live Camera (`/camera`), plus voice dictation.
- **Expiring Plan Alert**: When a plan is near expiry or when a user is on the Free tier, an animated, blinking Upgrade button pulses to guide users to plan upgrades. Free plan users are not shown add-on clutter.
