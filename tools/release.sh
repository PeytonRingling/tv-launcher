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
# Location guard: the weather location is entered on the TV (ZIP), never compiled in. Fail if any old build-time location
# value (legacy tvlauncher.weather.* in ~/.gradle/gradle.properties, TVL_WEATHER_* env) or a WEATHER_* BuildConfig field
# shows up anywhere in the APK.
needles=("WEATHER_PLACE" "WEATHER_LAT" "WEATHER_LON" "WEATHER_TZ")
while IFS='=' read -r k v; do [[ -n "$v" ]] && needles+=("$v"); done < <(grep -hs '^tvlauncher\.weather\.' ~/.gradle/gradle.properties; env | grep '^TVL_WEATHER_' || true)
tmp=$(mktemp -d); unzip -qo "$OUT" -d "$tmp"
for n in "${needles[@]}"; do
  [[ "$n" == America/* ]] && continue   # IANA zone names appear in the platform/ICU data of any app; not a location leak
  if grep -rqaF -- "$n" "$tmp"; then rm -rf "$tmp"; echo "FAIL: APK contains a compiled-in location value (${n:0:3}…)" >&2; exit 1; fi
done
rm -rf "$tmp"; echo "location check: no compiled-in location (${#needles[@]} values checked)"

if [[ "$PUBLISH" == "--publish" ]]; then
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "FAIL: HEAD isn't origin/main; merge and pull first" >&2; exit 1; }
  "${CMD[@]}"
else
  printf 'Dry run. To publish:\n  '; printf '%q ' "${CMD[@]}"; echo
fi
