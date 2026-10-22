# AGENTS.md: TV Launcher ("Glass Dock", com.peyton.tvlauncher)

Kotlin + Jetpack Compose home-screen app for Peyton's Hisense Google TV (D-pad only). Current release: **v4.2, versionCode 7** (gradle defaults already at v4.3 / 8 for the GitHub-updater release).

## Build / test
```bash
./gradlew test                       # Robolectric unit + screenshot tests (renders to screens/, git-ignored)
./gradlew testDebugUnitTest          # same, debug variant only
./gradlew assembleRelease -Pvc=8 -Pvn=4.3   # release APK -> app/build/outputs/apk/release/app-release.apk
tools/make-testapks.sh               # regenerate git-ignored testapks/ fixtures for UpdaterE2ETest (skipped if absent)
```
Needs JDK 17+ and an Android SDK with platform 34 and build-tools 34.0.0 (`local.properties` `sdk.dir=…` or `ANDROID_HOME`).

## Hard rules
* **Never run the Android emulator.** It crashes the build box. Verify UI with Robolectric/Roborazzi renders in `screens/`.
* **Signing:** releases must be signed with `~/.android/debug.keystore`, cert SHA-256
  `65f0d4cb5041d1d60f2fbbb44d0bc73174de9ea1668be0038129630a6a4d113f`, or the TV refuses the update. Never create or commit a key.
  Overrides: `tvlauncher.*` Gradle props / `TVL_*` env vars. See RELEASING.md (including how to supply the key on a new machine).
* **Versioning:** always bump with `-Pvc/-Pvn` (versionCode must increase) and update the defaults in `app/build.gradle.kts`.
* Never commit APKs, keystores, `local.properties`, or `testapks/`.
* The repo is public: never commit personal data (home location, LAN IPs, device serials, family names, Drive links, personal
  photos). The weather location is entered on the TV as a ZIP code (Location.kt); never compile a location into the APK.
* Animations must stay smooth. Peyton is picky: the drawer slides as one solid layer over a pre-blurred background, nothing
  pops in. Don't add per-frame allocations or offscreen alpha layers on large surfaces.
* The launcher never scans the TV's shared storage. Phone photo upload / updates run only on a LAN-only, PIN-protected server
  while that screen is open.

## Delivery
* Preferred: GitHub Releases. `tools/release.sh <vc> <vn>` builds + verifies; add `--publish` to create the release. Tag `v<vn>`,
  asset `tv-launcher-v<vn>-vc<vc>.apk` (the app parses this name). The TV installs it from Settings → System → Update launcher.
  The script also fails if any location value ended up in the APK.
* Fallback: upload `TV Launcher v<x.y>.apk` to Peyton's Google Drive; he installs it with the QR updater on the same screen.
* Publishing a release, uploading or sending anything needs his approval.
* Updater code: `GitHubReleases.kt` (API client, pure parsing/error mapping), `Updater.kt` (verification + PackageInstaller,
  shared with the QR path), `MainActivity` (flow + daily quiet check). Tests: `GitHubReleasesTest`, `GitHubUpdateTest`.

## Layout
`app/` source and tests · `scripts/` adb helpers for the TV (inventory, debloat SAFE/OPTIONAL/RISKY, restore, set/restore
launcher, screensaver) · `mockups/` design references · `tools/` artwork generator + test-fixture script · README.md (full
history and user-facing setup).
