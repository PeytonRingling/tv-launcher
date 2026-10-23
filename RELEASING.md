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
* `versionCode` **must be higher** than the installed one, or the updater refuses it. History: v4.0=5, v4.1=6, v4.2=7, v4.3=8, v4.4.2=14 (private Drive builds used 9–13).
  The defaults in `app/build.gradle.kts` track the current release (14 / "4.4.2"), so always pass `-Pvc/-Pvn` for a new one
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

On the original build box nothing needs to be set for signing.

## Weather location (entered on the TV, never compiled in)
Since v4.3 the location comes from a US ZIP code entered on the TV (Settings › Display › Weather location), resolved once via
Zippopotam.us (no key) and stored in the launcher's prefs; Open-Meteo picks the timezone (`timezone=auto`). There is no
build-time location any more: no `BuildConfig.WEATHER_*`, no `tvlauncher.weather.*` / `TVL_WEATHER_*` settings. A fresh install
or an update from v4.2 shows "Set your ZIP code…" on the weather cards until a ZIP is entered.
`tools/release.sh` fails if the APK contains any legacy location value (it greps the unpacked APK for the old
`tvlauncher.weather.*` values if a machine still has them, and for `WEATHER_*` BuildConfig fields).

## Verse of the day: the API.Bible key is never in the repo or a public APK
The verse card shows the NIV from **API.Bible** with Peyton's own free key (Starter plan, non-commercial). API.Bible's terms
forbid making the key available to anyone else, and this repo and its GitHub releases are public, so:
* **Public builds** (`tools/release.sh`) force an empty seed (`-PAPI_BIBLE_KEY=`), then fail if the APK contains the key
  (from the `API_BIBLE_KEY` env var or `~/.gradle/gradle.properties`, never printed), if `BuildConfig.API_BIBLE_SEED` isn't
  empty, if `API_BIBLE_KEY` appears in the repo's properties files, or if the dex looks like it contains Scripture text.
  Any other `assemble*` with `API_BIBLE_KEY` set but no `-Ptvlauncher.private=true` fails in Gradle.
* **Private build** for Drive only: `API_BIBLE_KEY=… tools/build-private.sh 8 4.3` → `release/private/tv-launcher-v4.3-vc8-private.apk`
  (git-ignored; same versionCode, signer checked). On first launch the app checks the seed with API.Bible once and moves it
  into private storage, exactly like the QR path. Later public updates have no seed and keep the stored key. Anyone with
  the private APK can extract the key: deliver it only through Peyton's own Drive, never GitHub. The script runs
  `./gradlew clean` at the end so no key-bearing build output is left in `app/build`.
* The private flag also seeds Settings › Verse of the day › **Show copyright on card** OFF, once, on first launch and only
  if it was never set (Peyton's personal choice; it departs from API.Bible's attribution terms, so public builds keep it ON).
  Bump the private versionCode above whatever is installed, e.g. `tools/build-private.sh 9 4.3.1` over the private vc 8.
* **No seed at all**: Settings › Verse of the day › Add API.Bible key shows a QR + PIN; paste the key on the phone.
* Removing the key in Settings also deletes the cached verses and stops a seeded build from re-adding it.

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

## Delivery 1: GitHub Releases + the in-app updater (preferred)
The launcher checks `https://api.github.com/repos/PeytonRingling/tv-launcher/releases/latest` (public, no token).

**Naming convention (the app depends on it):**
* tag `v<versionName>`, e.g. `v4.3`
* exactly one APK asset named `tv-launcher-v<versionName>-vc<versionCode>.apk`, e.g. `tv-launcher-v4.3-vc8.apk`

The updater reads the versionCode from the asset name (one API call, no APK download just to compare), offers the update
only if it's higher than the installed build, downloads it to the app cache, and before PackageInstaller sees it checks:
package `com.peyton.tvlauncher`, signer exactly `65f0d4cb…4a4d113f` (pinned), versionCode higher than installed **and**
equal to the one in the asset name. Other assets (`.idsig`, notes, other apps' APKs) are ignored.

```bash
tools/release.sh 8 4.3              # build + verify (aapt/apksigner) + refuse non-increasing versionCode; prints the gh command
tools/release.sh 8 4.3 --publish    # same, then `gh release create v4.3 release/tv-launcher-v4.3-vc8.apk ...` (Peyton's OK first)
```
`--publish` also requires HEAD == origin/main (merge first, so the tag points at the shipped code).

**Location check:** releases are location-free (see "Weather location" above); `release.sh` verifies that on the built APK.

On the TV: **Settings → System → Update launcher → Install x.y from GitHub** (or "Check GitHub for updates"). Android shows
its install prompt; the first time it also asks to allow "Install unknown apps" for TV Launcher. The launcher also checks
quietly at most once a day (20 s after start, never during the cold start) and only shows an "Update x.y available" notice
in Settings; it never installs on its own. Rate limits (60 unauthenticated calls/hour per IP) show as a friendly error.

## Delivery 2 (fallback): Google Drive + the in-app QR updater
(The private, key-seeded APK from `tools/build-private.sh` goes this way only. The QR updater needs a higher versionCode than the
one installed, so the private v4.3 (vc 8) installs over v4.2 (vc 7), not over a public vc 8.)
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
