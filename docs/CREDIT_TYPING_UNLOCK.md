# Credit typing unlock

Live credit check must never disable the composer.

Patched in BQSqwadApp.kt:
- creditBalanceLoaded starts true
- text field always enabled
- no "Checking live credits" placeholder
- no send block while balance is loading
- server still enforces real balance
