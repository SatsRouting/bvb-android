# BVB Android

Native Kotlin client for the BVB P2P Bitcoin marketplace. MVP scope: login /
avatar creation, marketplace order book, order creation with Lightning deposit,
full trade flow (escrow states, timelocks, consensual cancellation, claims),
end-to-end encrypted PGP chat, real-time updates via SSE, notifications and
profile (mnemonic / PGP key reveal).

## Stack

- Kotlin + Jetpack Compose (Material 3), single module, package-by-feature
- Hilt (DI), Retrofit + OkHttp + kotlinx.serialization (REST), OkHttp SSE
- PGPainless + BouncyCastle (PGP, same dual-encryption envelope as the web client)
- ZXing (invoice QR), EncryptedSharedPreferences + Keystore (JWT storage)
- minSdk 26, target/compile SDK 35

## Backend requirements

The app authenticates with `Authorization: Bearer` and identifies itself with
the `X-Client: mobile` header; the backend returns the JWT in the login body
for mobile clients (see `internal/api/auth_handlers.go`). Run a backend built
from this repository (commit including the mobile login change).

The REST contract consumed by the app is documented in
[`../docs/api/openapi.yaml`](../docs/api/openapi.yaml).

## Building

```bash
cd android
./gradlew :app:assembleDebug
```

Configuration (Gradle properties, e.g. `-PbvbBaseUrl=...`):

| Property | Default | Meaning |
|---|---|---|
| `bvbBaseUrl` | `http://10.0.2.2:8080` | Backend base URL (default = host loopback from the Android emulator) |
| `bvbTurnstileSiteKey` | empty | Cloudflare Turnstile site key; empty skips the widget (backend must have `Turnstile.Enabled=false`) |

Example against a production server:

```bash
./gradlew :app:assembleRelease \
  -PbvbBaseUrl=https://bvb.example.com \
  -PbvbTurnstileSiteKey=0x4AAA...
```

### Release signing

Create `android/keystore.properties` (gitignored):

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Generate a keystore with:

```bash
keytool -genkeypair -keystore release.keystore -alias bvb \
  -keyalg RSA -keysize 4096 -validity 10000
```

APKs are produced in `app/build/outputs/apk/{debug,release}/`.

### Verifying the APK signature (AppVerifier / GrapheneOS)

Official release builds are signed with a certificate whose SHA-256
fingerprint is:

```
com.bvb.android
CAC5CB9A971EF95E7A2C8651288AD0CE0C46DC7EAAE5468C4445DDBDA23A7E73
```

With [AppVerifier](https://github.com/soupslurpr/AppVerifier):
"Internal database: status not found" is expected — that database only
contains a short curated list of well-known apps. Verify manually instead:
share the APK (or the installed app) to AppVerifier, then compare the
fingerprint it shows with the value above, or copy the two lines above and
use AppVerifier's paste/compare function. If they match, the APK is genuine.

The same fingerprint can be checked from a computer with:

```bash
apksigner verify --print-certs bvb-<version>.apk
```

## Architecture notes

- `core/session/SessionManager` — JWT persisted in EncryptedSharedPreferences;
  the session password and decrypted PGP private key live **only in memory**
  and are wiped on logout (same model as the web client).
- `core/sse/SseClient` — single SSE connection with exponential backoff; emits
  a synthetic `sse_reconnected` event so screens re-fetch state they may have
  missed while offline.
- `core/pgp/PgpService` — mirrors `frontend/src/utils/pgp.js`: armored
  encrypt/decrypt, detached signatures, and the
  `{"for_recipient": ..., "for_sender": ...}` dual-encryption envelope used in
  trade chat.
- `feature/trade/TradeDetailViewModel` — the escrow state machine; actions
  that co-sign transactions (seller confirm, claims, cancel confirm) prompt for
  the password when it is not already cached in the session.
- Turnstile: when `bvbTurnstileSiteKey` is set, login and avatar creation
  render the widget in a WebView (`feature/auth/TurnstileWebView`) and forward
  the solved token in the request.

## Not yet implemented (post-MVP)

- Tor/Orbot support (planned phase 2)
- Admin features
