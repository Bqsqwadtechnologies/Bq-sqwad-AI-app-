# BQ Sqwad AI — Admin Provider and Web Search Control

## Source of truth

Provider selection, model routing, feature availability, credit pricing and provider credentials are server-controlled. The Android application consumes signed/validated remote configuration and does not contain provider secret values.

## Provider credentials

Configured server-side secret references:

- GEMINI_API_KEY
- OPENAI_API_KEY
- TAVILY_API_KEY
- PAYSTACK_SECRET_KEY

Public/payment reference:

- PAYSTACK_PUBLIC_KEY

The Android APK must never contain the values of these secrets. The admin UI may display provider configuration status and masked metadata, but secret values are written to the secure backend secret store.

## Default provider policy

- Main Chat: Gemini by default.
- Device/phone commands: Gemini by default.
- Web Search: Tavily by default.
- Other workspaces: admin-selected provider/model route.
- BQ Sqwad AI Agent/Codron: independently controllable and can be selected as a route where its capability is enabled.

These are defaults only. They are not hardcoded provider execution paths.

## Dynamic provider routing

Each capability has:

- capability ID
- primary provider
- primary model
- ordered fallback providers
- enabled/disabled state
- credit-cost policy ID
- plan/entitlement requirements

A remote configuration change can replace the primary provider or fallback chain without shipping a new APK.

## Web Search

Tavily is the default web-search provider.

Web search is available as a shared service to any workspace that has the capability and entitlement. This includes Main Chat, Live Camera, Live Voice, Personal Intelligence, Life Matters, Learning Studio, Business, Codron/agents and Deep Research.

The server must:

1. authenticate the account;
2. verify capability and plan entitlement;
3. resolve the configured web-search provider;
4. calculate the current credit reservation from admin pricing;
5. reserve credits;
6. execute the search;
7. commit the actual charge;
8. reverse unused reserved credits on failure/cancellation;
9. return source metadata/citations;
10. record usage and an audit event.

### Admin-configurable web-search pricing

Admin can change without an APK release:

- credits per normal search;
- credits per deep-research session;
- credits per result/page when enabled;
- maximum results;
- daily limits;
- plan-specific overrides;
- search capability availability;
- provider and fallback provider;
- provider/model configuration where supported.

The Android client must never calculate final billing itself. It displays server-provided estimates and final usage.

## Payment separation

Auto-renew is a Payment Settings control for the user. It is not part of the normal plan-selection/checkout form.

Checkout flow remains:

1. select plan;
2. request server payment preview;
3. show confirmation;
4. create server-side Paystack transaction;
5. redirect to Paystack;
6. receive return;
7. server verifies transaction;
8. server creates/updates entitlement;
9. app refreshes subscription and capabilities.

A browser return alone must never activate a plan.

## Admin controls

The Admin Control Center should expose:

- provider enable/disable;
- masked credential status;
- secret rotation/replacement;
- provider health;
- primary/fallback routing;
- model catalogue;
- feature flags;
- capability rollout;
- subscription requirements;
- credit pricing;
- plan limits;
- web-search pricing;
- Paystack configuration;
- testing mode;
- Codron controls;
- plugin/capability registration;
- audit/security;
- incident controls;
- emergency kill switches.

## Security requirements

Never:

- commit API secret values;
- log API secret values;
- place secret values in BuildConfig/resources;
- place secret values in Android source;
- expose provider secrets through client remote config;
- trust client-supplied provider/price/credit values.

The backend must enforce provider selection, entitlement, pricing, credits and payment verification.

## Status rule

This document defines the native/backend contract. A capability is operational only after backend integration, server-side authorization, provider calls, credit accounting, failure handling and real Android QA are verified.
