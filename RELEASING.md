# Releasing TV Launcher ("Glass Dock")

## The one rule: same signing key, every time
Every release of `com.peyton.tvlauncher` **must** be signed with the same key as all earlier versions:

| | |
|---|---|
| Keystore | `~/.android/debug.keystore` (standard Android debug keystore on the build box) |
| Alias / passwords | `androiddebugkey` / `android` / `android` (the public Android debug defaults) |
| Cert SHA-256 | `65f0d4cb5041d1d60f2fbbb44d0bc73174de9ea1668be0038129630a6a4d113f` |
| Cert DN | `C=US, O=Android, CN=Android Debug` |

If the cert changes, the in-app QR updater (Settings → System → Update launcher) rejects the APK ("different key")
and Android itself refuses to install it over the existing app. The only way out would be uninstalling the launcher
on the TV, which loses its settings, dock, and photos and means redoing the default-home setup. **Never generate a
new key, and never let a fresh machine auto-create its own `debug.keystore`.** AGP silently creates a *new* debug
keystore when none exists, and that one has a different cert.

The keystore is **not** in this repo (`*.keystore`, `*.jks`, `*.p12` are git-ignored). Never commit it.

## Build a release
```bash
./gradlew assembleRelease -Pvc=<versionCode> -Pvn=<versionName>
# e.g. v4.3:
./gradlew assembleRelease -Pvc=8 -Pvn=4.3
# -> app/build/outputs/apk/release/app-release.apk
```
* `versionCode` **must be higher** than the installed one, or the updater refuses it. History: v4.0=5, v4.1=6, v4.2=7.
  The defaults in `app/build.gradle.kts` track the current release (7 / "4.2"), so always pass `-Pvc/-Pvn` for a new one
  and update the defaults in the same commit.
* Verify before delivering:
  ```bash
  BT=$ANDROID_HOME/build-tools/34.0.0   # on the box: /home/box/android-sdk/build-tools/34.0.0
  $BT/aapt dump badging app/build/outputs/apk/release/app-release.apk | head -1      # package + versionCode
  $BT/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256
  # must print 65f0d4cb5041d1d60f2fbbb44d0bc73174de9ea1668be0038129630a6a4d113f
  ```
* Name it `TV Launcher v<x.y>.apk`. `release/` is git-ignored; APKs never go in git.

## Where the signing values come from
`app/build.gradle.kts` resolves each value as **Gradle property → environment variable → default**:

| Gradle property (`-P…` or `~/.gradle/gradle.properties`) | Env var | Default |
|---|---|---|
| `tvlauncher.storeFile` | `TVL_STORE_FILE` | `~/.android/debug.keystore` |
| `tvlauncher.storePassword` | `TVL_STORE_PASSWORD` | `android` |
| `tvlauncher.keyAlias` | `TVL_KEY_ALIAS` | `androiddebugkey` |
| `tvlauncher.keyPassword` | `TVL_KEY_PASSWORD` | `android` |

On the original build box nothing needs to be set for signing (the weather location comes from `~/.gradle/gradle.properties`, see below).

## Weather location (kept out of the repo)
The home-screen weather location is a build-time setting, resolved like the signing values (Gradle property → env var → default):

| Gradle property | Env var | Repo default |
|---|---|---|
| `tvlauncher.weather.place` | `TVL_WEATHER_PLACE` | `New York, NY` (label shown on the TV) |
| `tvlauncher.weather.lat` | `TVL_WEATHER_LAT` | `40.7128` |
| `tvlauncher.weather.lon` | `TVL_WEATHER_LON` | `-74.0060` |
| `tvlauncher.weather.tz` | `TVL_WEATHER_TZ` | `America/New_York` (IANA zone for the forecast hours) |

The real home location lives in `~/.gradle/gradle.properties` on the original build box, not in git. On any other machine or
cloud agent, set the four `TVL_WEATHER_*` values (as secrets/env) before building a release, or the TV will show the neutral
default city's weather.

## Building on another machine / a cloud agent (documented only, not set up)
Provide the *same* keystore as a secret, then decode it at build time. Never commit it.
1. On the box, once: `base64 -w0 ~/.android/debug.keystore` and store the output as a secret
   (e.g. a cloud-agent/CI secret named `TVL_KEYSTORE_B64`). Treat it as a secret even though the passwords are the public
   debug defaults, because whoever holds this file can ship "updates" the TV will accept.
2. In the build environment, before Gradle runs:
   ```bash
   mkdir -p ~/.android
   echo "$TVL_KEYSTORE_B64" | base64 -d > ~/.android/debug.keystore   # or any path + TVL_STORE_FILE=<path>
   chmod 600 ~/.android/debug.keystore
   keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
   # must show 65:F0:D4:CB:...:4A:4D:11:3F
   ```
   Decode it **before** the first Gradle build, so AGP doesn't auto-create a different debug keystore there.
3. Build and verify as above. If you have no access to the secret, do not ship. Build/test only and hand the release off.

## Testing: never run the emulator
* **Do not start the Android emulator.** It crashes the shared build box. Verify UI with Robolectric/Roborazzi
  screenshot renders instead: `./gradlew testDebugUnitTest` (or `./gradlew test`), then look at the PNGs written to `screens/`
  (`screens/` is git-ignored because every test run re-records it).
* `UpdaterE2ETest` needs the git-ignored fixtures in `testapks/`. Regenerate them with `tools/make-testapks.sh`, which builds dummy
  v4.0/v4.1 APKs plus a throwaway "other key" copy. Without them the test is skipped, not failed. Afterwards, rebuild the real
  release, because the script leaves `app-release.apk` at versionCode 6.

## Delivery: Google Drive + the in-app QR updater
1. Upload `TV Launcher v<x.y>.apk` to Peyton's **Google Drive** (root, next to earlier versions). Direct downloads from chat
   don't work on his phone, so always use Drive.
2. On the TV: Settings → System → **Update launcher**, then scan the QR with the phone, enter/confirm the 6-digit PIN, and pick
   the APK from Drive. The launcher checks the package name, signing cert and a higher versionCode, then installs it through
   PackageInstaller.
3. Uploading or sending anything needs Peyton's OK first.

---

# Tap Zoo (com.peyton.toddlergame) signing, for reference
Tap Zoo lives in its own repo but uses **the same key**: it ships as the **debug** build (`./gradlew assembleDebug`),
which AGP signs with `~/.android/debug.keystore`, cert SHA-256 `65f0d4cb…4a4d113f` (checked with apksigner on the
current build and on earlier Tap Zoo APKs on the box). It has no `signingConfigs` block, so the same "decode the keystore
before the first build" rule applies on any other machine. All Tap Zoo APKs inspected so far report `versionCode 1` (see that
repo's RELEASING.md).
