# BQ Sqwad AI — Live Production, Social Security & Media Rights Audit

## Implemented
- Existing YouTube/X OAuth remains the single provider integration.
- Provider access/refresh tokens are encrypted with the existing server-side secret store.
- YouTube live broadcast/stream creation and lifecycle are exposed through Organization AI Studio backend routes.
- YouTube OAuth refresh is handled server-side before live API calls.
- Media rights are now a server-side prerequisite for media publishing and YouTube live creation.
- Users submit a rights record; the only account allowed to approve it is the existing super-admin path.
- Admin endpoints expose pending rights and allow verified/rejected/needs-more-evidence decisions.
- Media with an external source URL cannot be published or used for YouTube live until its rights record is verified.
- Rights verification is intentionally not represented as legal advice or as a claim that YouTube/X have certified ownership. Provider API approval and a user's legal license are separate checks.
- Existing package, Firebase project, OAuth callback, social architecture, Business AI and Organization AI routes were preserved.

## Required Android/media pipeline completion
The repository inspection available through the connected GitHub interface did not expose the Android Live Camera/media encoder source files needed to safely modify the actual camera-to-encoder transport. Therefore no fake completion claim is made.

The remaining production path is:
Live Camera -> Organization AI Studio program output -> encoder/transport -> provider ingestion endpoint -> provider live session -> monitoring.

This must reuse the existing Live Camera implementation when its source is available; a second camera system must not be created.

## Copyright/rights policy
For TV, third-party video, music, sports or other externally owned material:
- owned material may be submitted as owned;
- licensed material requires a license reference and admin verification;
- public-domain material requires a rights basis;
- pending, rejected or insufficient evidence blocks publication/live use;
- provider/API status is checked separately and does not replace the rights review.

## Security
Secrets remain server-side. Client IDs may be public application identifiers; client secrets, bearer tokens, access tokens and refresh tokens must remain in the secure backend secret mechanism.

## Automation
No GitHub Actions workflow was started automatically as part of this change.
