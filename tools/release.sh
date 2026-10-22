#!/usr/bin/env bash
# Build, verify and (optionally) publish a TV Launcher release to GitHub Releases, where the in-app updater finds it.
#
#   tools/release.sh <versionCode> <versionName>            # build + verify + print the gh command (dry run)
#   tools/release.sh <versionCode> <versionName> --publish  # ...and run `gh release create` (needs Peyton's OK)
#
# Naming convention (the app depends on it, see RELEASING.md):
#   tag  v<versionName>                          e.g. v4.3
#   one asset  tv-launcher-v<versionName>-vc<versionCode>.apk   e.g. tv-launcher-v4.3-vc8.apk
set -euo pipefail
cd "$(dirname "$0")/.."
VC="${1:?versionCode}"; VN="${2:?versionName}"; PUBLISH="${3:-}"
CERT=65f0d4cb5041d1d60f2fbbb44d0bc73174de9ea1668be0038129630a6a4d113f
PKG=com.peyton.tvlauncher; REPO=PeytonRingling/tv-launcher
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/box/android-sdk}}"; BT="$SDK/build-tools/34.0.0"
TAG="v$VN"; NAME="tv-launcher-v$VN-vc$VC.apk"; OUT="release/$NAME"

[[ "$VC" =~ ^[0-9]+$ ]] || { echo "versionCode must be a number" >&2; exit 1; }
[[ "$VN" =~ ^[0-9][0-9A-Za-z.]*$ ]] || { echo "versionName must look like 4.3" >&2; exit 1; }

./gradlew -q assembleRelease -Pvc="$VC" -Pvn="$VN"
mkdir -p release && cp app/build/outputs/apk/release/app-release.apk "$OUT"

badging=$("$BT/aapt" dump badging "$OUT" | head -1)
echo "$badging"
[[ "$badging" == *"name='$PKG' versionCode='$VC' versionName='$VN'"* ]] || { echo "FAIL: package/version mismatch" >&2; exit 1; }
certs=$("$BT/apksigner" verify --print-certs "$OUT" | grep -o 'SHA-256 digest: [0-9a-f]*' | awk '{print $3}' | sort -u)
echo "signer: $certs"
[[ "$certs" == "$CERT" ]] || { echo "FAIL: not signed with the release key (see RELEASING.md)" >&2; exit 1; }

# Refuse to go backwards against what's already published (no token needed; the repo is public).
latest=$(gh release view --repo "$REPO" --json assets -q '.assets[].name' 2>/dev/null | sed -n 's/.*-vc\([0-9]*\)\.apk$/\1/p' | sort -n | tail -1 || true)
if [[ -n "$latest" && "$VC" -le "$latest" ]]; then echo "FAIL: versionCode $VC is not above the latest release ($latest)" >&2; exit 1; fi

CMD=(gh release create "$TAG" "$OUT" --repo "$REPO" --target main --title "TV Launcher $VN" --notes "TV Launcher $VN (build $VC). Install from the TV: Settings › System › Update launcher › Install from GitHub.")
echo "OK: $OUT"
# The weather location is baked into the APK (BuildConfig). A public release asset would publish it.
if grep -qs '^tvlauncher\.weather\.' ~/.gradle/gradle.properties || env | grep -q '^TVL_WEATHER_'; then
  echo "WARNING: this APK contains the private weather location (tvlauncher.weather.* / TVL_WEATHER_*); anyone can download a public release." >&2
  PRIVATE_LOCATION=1
fi
if [[ "$PUBLISH" == "--publish" ]]; then
  if [[ -n "${PRIVATE_LOCATION:-}" && "${TVL_ALLOW_LOCATION_IN_APK:-}" != 1 ]]; then
    echo "FAIL: refusing to publish a location-bearing APK. Build without the weather props, or set TVL_ALLOW_LOCATION_IN_APK=1 if Peyton OKs it." >&2; exit 1
  fi
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "FAIL: HEAD isn't origin/main; merge and pull first" >&2; exit 1; }
  "${CMD[@]}"
else
  printf 'Dry run. To publish:\n  '; printf '%q ' "${CMD[@]}"; echo
fi
