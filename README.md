# Bitcoin Voucher Bot — Android

Native Android client for the [Bitcoin Voucher Bot](https://p2p.bitcoinvoucher.bot)
— a **non-custodial** peer-to-peer Bitcoin / Lightning marketplace.

This repository hosts both the **full app source code** and the **signed APK
releases**. If you use [Obtainium](https://github.com/ImranR98/Obtainium)
you'll get update notifications automatically.

## 📥 Install

1. Open the [**Latest release**](../../releases/latest) and download the `.apk`.
2. On your phone, allow installation from unknown sources when prompted.
3. Install and open the app.

Requires **Android 8.0** (API 26) or newer.

### Automatic updates with Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. **Add App** → paste this repository URL:

   ```
   https://github.com/SatsRouting/bvb-android
   ```

3. Obtainium tracks GitHub releases and offers new versions automatically.

## 🔐 Verify before installing

Every release is signed with the **same** key, so updates install in place.
Verify the signing certificate with
[AppVerifier](https://github.com/soupslurpr/AppVerifier):

```
Package:  com.bvb.android
SHA-256:  CAC5CB9A971EF95E7A2C8651288AD0CE0C46DC7EAAE5468C4445DDBDA23A7E73
```

The **per-file APK SHA-256** is published in each release's notes.

## 🛠️ Build from source

The app is a standard Gradle / Jetpack Compose project (Kotlin, JDK 17).

```bash
# The base URL and the (public) Cloudflare Turnstile site key are build inputs:
./gradlew :app:assembleRelease \
  -PbvbBaseUrl=https://p2p.bitcoinvoucher.bot \
  -PbvbTurnstileSiteKey=0x4AAAAAAB4U6O83PyG_ydwa
```

To produce a signed build, copy `keystore.properties.example` to
`keystore.properties` and fill in your own keystore details (never commit it —
it is git-ignored). Official releases are signed with our key; verify the
signing certificate as described above. A helper script builds, signs and
prints the fingerprints in one step:

```bash
./scripts/release.sh
```

No secrets are embedded in the source: the API base URL and the Turnstile site
key are public, and are passed at build time.

## ✨ Features

- Login and avatar creation (Cloudflare Turnstile)
- Marketplace order book with filters, reputation and payment method icons
- Order creation with Lightning deposit
- Full trade flow: escrow, timelocks, consensual cancellation, claims
- End-to-end encrypted PGP chat with image attachments
- Disputes with text / file / chat evidence
- Mullvad VPN voucher purchase via Lightning
- Real-time updates (SSE) and local notifications
- App lock (fingerprint / face / device PIN) for the app and sensitive actions
- Wallet recovery, referral program, Telegram linking

## 🔗 Links

- Web app: https://p2p.bitcoinvoucher.bot
- Telegram bot: https://t.me/BitcoinVoucherBot

## ⚠️ Disclaimer

Beta software, provided **as is**, without warranty. The marketplace is
non-custodial: you are responsible for your keys, your recovery phrase and your
funds. Always verify the signing certificate above before installing.
