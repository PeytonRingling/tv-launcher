#!/usr/bin/env bash
# Regenerates the git-ignored fixtures used by UpdaterE2ETest (testapks/):
#   v40.apk           versionCode 5 "4.0", release key (the "installed" app)
#   v41.apk           versionCode 6 "4.1", release key (a valid update)
#   v41_otherkey.apk  versionCode 6 "4.1", re-signed with a throwaway key (must be rejected)
#   other.jks         the throwaway key (CN=Not Peyton). NOT a real key; still never committed.
# Metadata is what matters (package, versionCode, cert); the code inside is whatever is checked out.
# Usage: tools/make-testapks.sh [outdir]   (default: testapks/). Needs the release key set up (see RELEASING.md).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-testapks}"; mkdir -p "$OUT"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/box/android-sdk}}"; BT="$SDK/build-tools/34.0.0"
APK=app/build/outputs/apk/release/app-release.apk
./gradlew -q assembleRelease -Pvc=5 -Pvn=4.0 && cp "$APK" "$OUT/v40.apk"
./gradlew -q assembleRelease -Pvc=6 -Pvn=4.1 && cp "$APK" "$OUT/v41.apk"
[ -f "$OUT/other.jks" ] || keytool -genkeypair -keystore "$OUT/other.jks" -storepass otherpass -keypass otherpass \
  -alias other -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Not Peyton" >/dev/null
"$BT/apksigner" sign --ks "$OUT/other.jks" --ks-pass pass:otherpass --ks-key-alias other \
  --out "$OUT/v41_otherkey.apk" "$OUT/v41.apk"
for f in "$OUT"/v40.apk "$OUT"/v41.apk "$OUT"/v41_otherkey.apk; do
  echo "$f: $("$BT/aapt" dump badging "$f" | head -1 | grep -o "versionCode='[0-9]*'") $("$BT/apksigner" verify --print-certs "$f" | grep -o 'SHA-256 digest: [0-9a-f]*')"
done
echo "Done. Rebuild your real release afterwards (assembleRelease -Pvc=<n> -Pvn=<x.y>); this left app-release.apk at vc 6."
