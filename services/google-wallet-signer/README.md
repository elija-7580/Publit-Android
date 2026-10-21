# Optional Google Wallet signer

Not deployed or enabled by default. Requires your own Google Wallet issuer account, an approved generic pass class,
a service account authorized for that issuer, and a deployed HTTPS endpoint. This does **not** issue valid transport tickets.

1. Create an approved Google Wallet generic class. Set `GOOGLE_ISSUER_ID`, `GOOGLE_CLASS_ID`, `GOOGLE_CLIENT_EMAIL`
   and comma-separated `WALLET_ORIGINS` to the approved web origins.
2. Put the service account's PKCS#8 RSA key in the **secret** `GOOGLE_PRIVATE_KEY`. Never commit it or ship it in an APK.
3. Deploy `worker.mjs` with a Cloudflare `RATE_LIMITER` binding (e.g. 10 requests/minute/IP). It fails closed without it.
   Add an overall request budget/monitoring. This public copy-signing endpoint is not proof that a ticket is genuine.
   If you need authenticated users, add a server-verified identity/attestation layer; do not embed a shared secret in the app.
4. Build the phone app with `-Ppublit.walletSignerUrl=https://your-host/sign` in the Gradle arguments.

The button is hidden without a configured HTTPS endpoint or for unsupported/binary barcodes. The user must confirm
before the title, operator, route, validity dates and static barcode are sent. No passenger name, original document,
image or notes are sent. The service does not log or store ticket data. Google receives the pass when the user saves it.
Update the deployed service privacy policy and Play Data Safety declaration before enabling this option.

Tests: `node --test services/google-wallet-signer/worker.test.mjs` (no account or real private key needed).
