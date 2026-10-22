#!/usr/bin/env bash
# PRIVATE TV Launcher build with Peyton's API.Bible key seeded in, for delivery via his own Google Drive ONLY.
#
#   API_BIBLE_KEY=… tools/build-private.sh [versionCode] [versionName]     # default 8 / 4.3
#
# Output: release/private/tv-launcher-v<vn>-vc<vc>-private.apk (git-ignored). On first launch the app checks the key with
# API.Bible once and moves it into private storage (same place as the QR path); later PUBLIC updates (no seed) keep it.
# Never upload this APK to GitHub or share it: anyone with the file can extract the key. The key is never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
VC="${1:-8}"; VN="${2:-4.3}"
CERT=65f0d4cb5041d1d60f2fbbb44d0bc73174de9ea1668be0038129630a6a4d113f
PKG=com.peyton.tvlauncher
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/box/android-sdk}}"; BT="$SDK/build-tools/34.0.0"
OUT="release/private/tv-launcher-v$VN-vc$VC-private.apk"

[[ -n "${API_BIBLE_KEY:-}" ]] || { echo "FAIL: set API_BIBLE_KEY in the environment (never on the command line or in a file in the repo)" >&2; exit 1; }
[[ "$API_BIBLE_KEY" =~ ^[A-Za-z0-9_-]{16,128}$ ]] || { echo "FAIL: API_BIBLE_KEY doesn't look like an API.Bible key" >&2; exit 1; }
git check-ignore -q "$OUT" || { echo "FAIL: $OUT isn't git-ignored" >&2; exit 1; }

# The key reaches Gradle through the environment only (not -P), so it never shows up in the process list.
./gradlew -q assembleRelease -Pvc="$VC" -Pvn="$VN" -Ptvlauncher.private=true
mkdir -p release/private && cp app/build/outputs/apk/release/app-release.apk "$OUT"

badging=$("$BT/aapt" dump badging "$OUT" | sed -n 1p)
echo "$badging"
[[ "$badging" == *"name='$PKG' versionCode='$VC' versionName='$VN'"* ]] || { echo "FAIL: package/version mismatch" >&2; exit 1; }
certs=$("$BT/apksigner" verify --print-certs "$OUT" | grep -o 'SHA-256 digest: [0-9a-f]*' | awk '{print $3}' | sort -u)
echo "signer: $certs"
[[ "$certs" == "$CERT" ]] || { echo "FAIL: not signed with the release key (see RELEASING.md)" >&2; exit 1; }
tmp=$(mktemp -d); unzip -qo "$OUT" -d "$tmp"
grep -rqaF -- "$API_BIBLE_KEY" "$tmp" || { rm -rf "$tmp"; echo "FAIL: the key seed isn't in the private APK" >&2; exit 1; }
rm -rf "$tmp"
# Don't leave key-bearing build outputs behind in app/build (the next public build recompiles from scratch anyway).
./gradlew -q clean
echo "OK (private): $OUT contains the key seed. Deliver it only via your private Drive; never attach it to a GitHub release."
