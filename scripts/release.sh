#!/usr/bin/env bash
#
# Reproducible release build for the Bitcoin Voucher Bot Android app.
#
#   - builds a signed release APK with the (public) production inputs
#   - renames it to bvb-<versionName>.apk
#   - prints the APK SHA-256 and the signing certificate SHA-256
#   - fails if the signing certificate does not match the expected fingerprint
#
# Signing uses android/keystore.properties (git-ignored). No secrets live here:
# the base URL and the Cloudflare Turnstile site key are public build inputs.
#
# Usage:
#   ./scripts/release.sh
#
# Optional overrides via environment:
#   BVB_BASE_URL, BVB_TURNSTILE_SITE_KEY, JAVA_HOME, ANDROID_SDK_ROOT
#
set -euo pipefail

# --- Public build inputs (safe to commit) -----------------------------------
BASE_URL="${BVB_BASE_URL:-https://p2p.bitcoinvoucher.bot}"
TURNSTILE_KEY="${BVB_TURNSTILE_SITE_KEY:-0x4AAAAAAB4U6O83PyG_ydwa}"

# Expected release signing certificate. Never changes (same keystore forever);
# this is the value users verify with AppVerifier.
EXPECTED_CERT_SHA256="CAC5CB9A971EF95E7A2C8651288AD0CE0C46DC7EAAE5468C4445DDBDA23A7E73"

# --- Locate the Gradle project (this script lives in <project>/scripts) ------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

# --- Toolchain --------------------------------------------------------------
# JDK 17+ (a full JDK, needed for jlink). Falls back to a known local path.
if [ -z "${JAVA_HOME:-}" ] && [ -d /home/luke/tools/jdk-21.0.11+10 ]; then
  export JAVA_HOME=/home/luke/tools/jdk-21.0.11+10
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Android SDK build-tools (for aapt2 + apksigner). Auto-picks the newest.
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/luke/android-sdk}}"
BT="$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -n1 || true)"
if [ -z "$BT" ]; then
  echo "ERROR: Android build-tools not found under $SDK_DIR/build-tools" >&2
  echo "       set ANDROID_SDK_ROOT to your SDK location." >&2
  exit 1
fi

if [ ! -f keystore.properties ]; then
  echo "ERROR: keystore.properties not found in $PROJECT_DIR" >&2
  echo "       copy keystore.properties.example and fill in your signing key." >&2
  exit 1
fi

# --- Build ------------------------------------------------------------------
echo "==> Building signed release APK"
echo "    BASE_URL=$BASE_URL"
./gradlew :app:assembleRelease \
  -PbvbBaseUrl="$BASE_URL" \
  -PbvbTurnstileSiteKey="$TURNSTILE_KEY"

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "ERROR: expected APK not found at $APK" >&2
  exit 1
fi

# --- Version + artifact name ------------------------------------------------
BADGING="$("$BT/aapt2" dump badging "$APK")"
VN="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
VC="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
OUT="bvb-${VN}.apk"
cp -f "$APK" "$OUT"

# --- Report -----------------------------------------------------------------
echo
echo "======================================================================"
echo " Release artifact : $OUT   (versionName $VN, versionCode $VC)"
echo "----------------------------------------------------------------------"
echo " APK SHA-256:"
sha256sum "$OUT" | awk '{print "   "$1}'

CERT="$("$BT/apksigner" verify --print-certs "$APK" \
  | grep -i 'certificate SHA-256' | awk '{print toupper($NF)}')"
echo " Signing cert SHA-256:"
echo "   $CERT"

if [ "$CERT" = "$EXPECTED_CERT_SHA256" ]; then
  echo "   OK: matches the expected release certificate."
else
  echo "   ERROR: certificate does NOT match the expected fingerprint!" >&2
  echo "   expected: $EXPECTED_CERT_SHA256" >&2
  exit 1
fi
echo "======================================================================"
echo
echo "Next: create a GitHub release with tag v$VN, attach $OUT,"
echo "and paste the APK SHA-256 above into the release notes."
