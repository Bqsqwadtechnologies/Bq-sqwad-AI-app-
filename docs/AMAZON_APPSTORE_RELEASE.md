# BQ Sqwad AI — Amazon Appstore Release Lane

## Scope and isolation

- Repository: `Bqsqwadtechnologies/Bq-sqwad-AI-app-`
- Branch: `main`
- Android application ID: `com.bqsqwad.ai` (unchanged)
- Amazon build type: `amazon`
- Amazon artifacts: `app/build/outputs/apk/amazon/app-amazon.apk` and `app/build/outputs/bundle/amazon/app-amazon.aab`
- Google Play continues to use the existing `release` build type and existing `release-apk.yml` workflow.
- Website billing and Google Play product IDs are not changed by this Amazon build lane.

The Amazon lane is deliberately a separate Android build type rather than a product flavor. This avoids changing the existing `assembleRelease` and `bundleRelease` task names and their expected output paths.

## Build workflow

Run only from GitHub Actions → **BQ Sqwad AI — Amazon Appstore Build** → **Run workflow**.

The workflow is manual-only. It builds and tests the Amazon variant, verifies the package ID and APK/AAB signatures, and uploads two separate artifacts. It does not publish to Amazon.

## GitHub Actions secrets

The Amazon workflow reuses the existing release-signing secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Add this app-specific public key secret after downloading the PEM file from the Amazon Developer Console:

- `AMAZON_APPSTORE_PUBLIC_KEY_BASE64`

This is the Appstore SDK public authentication key, not the RVS shared secret. Store the PEM file's base64-encoded contents in the Actions secret. The workflow restores it only into the Amazon build source set at `app/src/amazon/assets/AppstoreAuthenticationKey.pem`; it is not committed to Git.

## Amazon SDK and manifest

The Amazon build type includes Amazon Appstore SDK 3.0.9 and merges the Amazon-only manifest at `app/src/amazon/AndroidManifest.xml`. That manifest adds the Appstore and App Tester package visibility declarations and the Amazon IAP response receiver. The Google Play manifest is unchanged.

Official SDK guidance:
- https://developer.amazon.com/docs/appstore-sdk/integrate-appstore-sdk
- https://developer.amazon.com/docs/in-app-purchasing/iap-implement-iap
- https://developer.amazon.com/docs/in-app-purchasing/iap-rvs-for-android-apps

## Billing integration status — not yet production-ready

Adding the SDK dependency and callback receiver does not by itself implement a secure checkout. The current native checkout screen and purchase verifier are Google Play-specific. Do not submit the Amazon build as a paid/credit-purchasing release until the Amazon-specific purchase adapter and server verification path are completed and tested.

The remaining Amazon billing work is:

1. Add an Amazon IAP client that requests Amazon product data, launches Amazon SKU purchases, handles purchase updates, and restores purchase history with `getPurchaseUpdates()`.
2. Define Amazon SKU mappings in a distinct server-owned catalog (do not reuse or rename Google Play product/base-plan IDs).
3. Add an authenticated Worker verification endpoint that calls Amazon RVS server-side using the app's Amazon user ID and receipt ID. The RVS shared secret must remain a Worker secret named `AMAZON_IAP_SHARED_SECRET`; it must never be embedded in the APK or committed.
4. Bind verified Amazon receipts to the signed-in Firebase UID, enforce receipt ownership and idempotency, and only then grant the mapped credits or entitlement through the existing server-owned credit/entitlement logic.
5. Handle canceled/invalid receipts, subscription renewal/expiry, restore, pending states, and retryable RVS errors.
6. Add automated tests with mocked RVS responses and a Fire OS device test using Amazon's App Tester / Live App Testing before production submission.

No Amazon RVS secret or Amazon SKU mapping is currently assumed or fabricated. The Amazon shared secret must be obtained from the Developer Console by the account owner. Amazon SKU entries must be created in that console and then matched to the server catalog.

## Developer Console listing checklist

Create the BQ Sqwad AI app listing under the correct Amazon developer account, using package name `com.bqsqwad.ai`. Complete the current App Setup sections:

- General Information: app name **BQ Sqwad AI**, developer/publisher **Bq Sqwad Technologies**, app category and supported languages.
- Store Listing Information: original app icon, promotional image, accurate short and full descriptions, and real screenshots captured from supported Fire OS devices.
- Pricing and Availability: choose free app download unless a paid download model is explicitly approved; configure territories and availability.
- Policies and Compliance: complete content rating, privacy/data disclosures, permissions, export/compliance questions, and privacy-policy URL truthfully.
- Release Tracks: upload the signed Amazon APK or AAB, select supported Fire OS devices, and submit for review.

Amazon accepts APK or AAB for Fire OS. AAB is optional. Amazon currently documents AAB handling and device support separately; validate the uploaded artifact in the Developer Console and Live App Testing.

Official submission guidance:
- https://developer.amazon.com/docs/app-submission/submitting-apps-to-amazon-appstore
- https://developer.amazon.com/docs/app-submission/app-bundles
- https://developer.amazon.com/docs/app-submission/device-filtering-and-compatibility

## Release safeguards

- Do not change `applicationId`, Google Play billing IDs, Play signing, Firebase identity, website billing, or the existing Google Play release workflow as part of Amazon setup.
- Keep the Amazon RVS shared secret server-side.
- A successful CI build is not proof of Amazon IAP operation or Appstore approval.
- The workflow does not upload, submit, or publish anything to Amazon.
