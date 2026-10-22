# TV Launcher v1 — a clean home screen for Google TV (built for a Hisense Google TV)

A replacement **Home screen** app. It is *not* a new operating system: Google TV, the Play Store, your apps,
updates and Chromecast keep working underneath. Uninstall it (or run `scripts/restore-stock-launcher.sh`) and the TV
is exactly as before. Everything is driven by the remote's D-pad.

## What's in it
- **Home**: big clock + date, live weather (Open-Meteo, no account/key; location from a ZIP code entered in Settings › Weather location), a **Favorites** row and an
  **All apps** grid with each app's TV banner (or icon). Frosted-glass background made from your ambient photos.
  Smooth scale-and-glow focus animation. No ads, no "recommended for you" rows.
- **Hold OK** (or press the Menu button) on any app → Open · Add/Remove Favorite · **Move** (◀ ▶ to reorder, OK to finish)
  · Hide app · App info.
- Top-right shortcuts: **Inputs** (lists HDMI sources and switches to them; falls back to the TV's input/Live TV screen),
  **Ambient**, **Settings** (idle timer, clock 12/24 h, °F/°C, weather on/off, hidden apps, Android TV settings).
- **Ambient screen**: after 5 min idle on the home screen (change in Settings, or start it on demand) a slow Ken Burns
  slideshow with clock and weather. Any button returns (volume still works). Photos come from
  `Pictures/Ambient` or `Download/Ambient` on the TV; 5 built-in landscapes are used when that folder is empty.
- **Optional system screensaver**: the same ambient screen can replace Google's screensaver (`scripts/ambient-screensaver.sh on`).

## 1. Install the APK on the TV
First, on the TV: **Settings → System → About → Android TV OS build** — press OK on it ~7 times until it says
you're a developer. Then **Settings → System → Developer options → USB debugging: On** (needed for adb, harmless otherwise).

Pick one:
- **Downloader app (easiest, no computer)**: install *Downloader* by AFTVnews from the Play Store. Allow it to install
  apps: **Settings → Apps → Security & restrictions → Unknown sources → Downloader: On**. In Downloader enter a direct
  download link to the APK (e.g. a file you host yourself, shared as "Anyone with the link") → Install.
- **Send Files to TV** (Play Store, on both phone and TV): send `TV Launcher v1.apk` from the phone, then open it on the TV
  with a file manager (e.g. *File Commander* or *X-plore*) and install. The same Unknown-sources switch applies to that app.
- **adb over Wi-Fi** from a computer (Android platform-tools):
  ```
  adb connect 192.168.x.x:5555        # TV IP: Settings → Network & Internet → your Wi-Fi
  adb install -r "TV Launcher v1.apk"
  ```
  Accept the "Allow USB debugging?" prompt on the TV the first time. (On newer firmware that shows *Wireless debugging*
  instead, use its "Pair device with pairing code" screen: `adb pair IP:PORT CODE`, then `adb connect IP:PORT`.)

Open it once from **Apps** to try it. Nothing changes until you make it the default (step 2).

## 2. Make it the default Home screen (Hisense Google TV)
Google TV does not offer a "default home app" picker, and Hisense sets are known to **snap back to the Google TV home
after the TV is powered off** even when another launcher is chosen. The reliable fix is adb:

```
cd scripts
TV=192.168.x.x ./set-default-launcher.sh                  # 1) try the soft way first (set-home-activity only)
TV=192.168.x.x ./set-default-launcher.sh --disable-stock  # 2) if Home still opens Google TV (typical on Hisense)
```
`--disable-stock` runs, in this order:
```
adb shell cmd package set-home-activity com.peyton.tvlauncher/.MainActivity
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx      # Google TV home screen
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # otherwise grabs HOME back
```
**Risks of disabling the Google TV home (launcherx)**, all fixed by the restore script:
- Google TV's own home features go away: the profile/"For you" rows, the top-right quick-settings/notification panel
  (use the gear in TV Launcher → *Android TV settings* instead), and the **Google Home smart-home panel**.
- Google TV system updates may re-enable it; just run the script again.
- If anything goes wrong you can't get stuck: TV Launcher is the Home app, Settings is reachable from it, and adb works.
  Worst case a factory reset re-enables everything.
- Hisense notes: the remote's **Input**, **Netflix / YouTube / Prime** buttons and the Hisense quick menu are handled by
  Hisense's own services and keep working. Avoid "accessibility service" style launcher overrides — on Hisense they cause
  a known double-jump D-pad bug; TV Launcher doesn't use one. *Launcher Manager 1.0.4 (com.wolf.google.lm)* is the
  no-adb alternative people use on Hisense if you'd rather not use a computer.

**Undo:** `TV=192.168.x.x ./scripts/restore-stock-launcher.sh` (re-enables both packages and sets Google TV home back).

## 3. Debloat (optional, reversible)
`scripts/debloat.sh` only **disables** packages for the main user (`pm disable-user --user 0`), logs every change to
`debloat-log.txt`, skips names not present on your TV, and refuses protected system packages (settings, remote, HDMI/
inputs, Play services/Store, keyboard, Live TV, MediaTek TV services, Hisense source/key handlers).

```
cd scripts
TV=192.168.x.x ./inventory.sh                    # read-only: saves model + package list (send it back to tailor the list)
TV=192.168.x.x ./debloat.sh --dry-run --optional # preview
TV=192.168.x.x ./debloat.sh                      # SAFE tier
TV=192.168.x.x ./debloat.sh --optional           # + OPTIONAL tier
TV=192.168.x.x ./restore.sh                      # undo everything from the log  (or ./restore.sh <package>)
```

| Tier | Package | What it is / what you lose |
|---|---|---|
| SAFE | com.google.android.tvrecommendations | Recommendation/ad feed (may not exist on Google TV) |
| SAFE | com.google.android.partnersetup | Partner promo + usage stats setup |
| SAFE | com.google.android.feedback, com.android.tv.feedbackconsent, com.google.android.tv.bugreportsender | Crash/feedback reporting |
| SAFE | tv.samba.ssm | Samba TV ad tracking, if present |
| SAFE (verify) | com.hisense.hireport, com.hisense.hitv.das | Hisense telemetry / analytics |
| SAFE (verify) | com.hisense.service.ad, com.hisense.business.ad, com.hisense.tv.advertisementproxy, com.hisense.magic.ad | Hisense ad services |
| SAFE (verify) | com.hisense.demonstrate, com.hisense.electronictutorial | Store demo, tutorial |
| SAFE | com.android.printspooler | Printing |
| OPTIONAL | com.google.android.videos, com.google.android.play.games, com.google.android.youtube.tvmusic, com.google.android.youtube.tvkids | Google TV Shop/Movies, Play Games, YouTube Music, YouTube Kids |
| OPTIONAL | com.google.android.apps.tv.dreamx | Google Ambient Mode screensaver (also hosts the Google Home panel) |
| OPTIONAL | com.android.dreams.basic | Basic screensavers |
| OPTIONAL (verify) | com.mediatek.AirplayAPK, com.mediatek.airplaydaemon, com.mediatek.partner.airplay_sys | AirPlay receiver |
| OPTIONAL | com.android.adservices.api, com.android.ondevicepersonalization.services, com.android.federatedcompute.services | Ad measurement / personalization |
| OPTIONAL (off by default, uncomment) | Prime Video, Pluto TV, Tubi, Xumo Play, Fandango at Home, Sling, Peacock, Netflix, YouTube | Preinstalled partner apps — the matching remote button stops working |
| RISKY | com.google.android.katniss | Google Assistant: voice search on the remote stops |
| RISKY | com.vt.tv.ota (U8K/U8N confirmed), com.hisense.tv.ota | Hisense firmware updates stop |
| **NEVER** | com.android.tv.settings, systemui, gms/gsf, com.android.vending, tv.remote.service, keyboards, com.android.tv, com.mediatek.tis / dtv / wwtv, anything Hisense with source / hdmi / tvinput / remote / bluetooth / pair / tiletransition / prism | Boot loops, dead remote, or no HDMI inputs |

"(verify)" names come from Hisense Android/VIDAA-era firmware reports; Hisense Google TV firmware differs by model and
year (e.g. U6/U7/U8 K/N/Q series use `com.vt.*` names), which is why `inventory.sh` exists.

## 4. Your photos on the ambient screen
Settings → *Use my photos* → allow access. Then put pictures in `Pictures/Ambient` on the TV:
```
adb shell mkdir -p /sdcard/Pictures/Ambient
adb push ~/Pictures/family/*.jpg /sdcard/Pictures/Ambient/
```
or with Send Files to TV / a file manager into `Download/Ambient`. Settings → *Rescan*.

## 5. Verse of the day (NIV via API.Bible, optional)
The **Verse of the day** home card (Settings › Home cards, or Settings › Verse of the day › Show on home) shows one short verse a
day from the NIV, fetched from [API.Bible](https://api.bible) with your own free key. No Scripture text is in this app or repo;
only a list of 366 references (`VerseRefs.kt`).
1. Sign up at **api.bible**, create an app, choose the free **Starter** plan (non-commercial), and add **New International
   Version** to it (Customize your app → "+"). Copy the app's API key. Keep it private: don't paste it into chats, issues or this repo.
2. On the TV: Settings › Verse of the day › **Add API.Bible key**, scan the QR with your phone (same Wi-Fi), enter the code,
   paste the key and press Save. The TV checks it once, finds the NIV, and stores the key only in the launcher's private
   storage (excluded from backups and device transfer). (A private build can carry the key instead; see RELEASING.md.)
3. The TV fetches today + the next 14 days at once (about one API call a day, far under Starter's 5,000/month), keeps them for
   at most 30 days, and shows the verse and its reference with the short-form notice API.Bible allows when space is limited
   (e.g. "Psalm 46:10 · NIV © 2011 Biblica, Inc. All rights reserved."). The full copyright text and "Powered by API.Bible —
   api.bible" are always in Settings › Verse of the day (About & credits). **Show copyright on card** (default **On**) controls
   the short notice; API.Bible's terms expect it, so leave it on unless the TV is purely for your own use. Each time the
   verse is shown it sends API.Bible's anonymous FUMS usage report. Without a key the card says "Add your API.Bible key in Settings."

## Build
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Screenshots (Robolectric, 1920×1080, no emulator): `./gradlew testDebugUnitTest` → `screens/`.
Releases, signing key and delivery: see [RELEASING.md](RELEASING.md). Notes for coding agents: [AGENTS.md](AGENTS.md).
Built-in wallpapers (`app/src/main/assets/wallpapers/`) and the photos in `mockups/img/` and `app/src/test/resources/photos/` are
stock photos from Unsplash via Lorem Picsum (picsum.photos), used under the [Unsplash License](https://unsplash.com/license).
`tools/landscapes.py` is an older procedural landscape generator (original artwork, free to use).
App names/logos in `mockups/` and `app/src/test/resources/fake/` are simplified stand-ins used only as test/design fixtures;
all trademarks belong to their owners.

## v3 (3.0)
- Release build (not debuggable), same signing key as v1/v2 → installs over them as an update. Baseline profile via profileinstaller.
- Smoother motion: dock/grid focus uses springs in graphicsLayer only; All Apps panel is prewarmed and slides via graphicsLayer (emphasized easing); LazyVerticalGrid with stable keys; held D-pad paced to ~9 steps/s; 900 ms wallpaper crossfade; clock isolated so it no longer recomposes the whole home screen every second.
- Uniform icons: every app is normalized to a full-bleed square tile (adaptive layers, trimmed legacy icons on their own edge colour, bare logos on a contrasting tile, banner-only apps).
- Readable glass: card darkness adapts to the wallpaper luminance under each card (targets ~4.5:1 for white text), adaptive top scrim, larger secondary text with shadows.
- Photos from phone: Settings → Ambient → "Add photos from phone" shows a QR code + URL with a 6-digit code. A LAN-only server (ports 8765-8775) runs only while that screen is open; photos are resized on the phone to 2560 px, stored in app-private storage, and can be deleted under "Manage my photos". The TV's shared storage is no longer read (storage permissions removed).
- Google Photos Picker API: not implemented — it requires an OAuth client in a Google Cloud project. The phone page's file picker can already pick from Google Photos on Android.

## v3.1 (versionCode 4) — drawer/animation smoothness
- Drawer slide is a pure translation of one layer: no fractional alpha on large layers (no offscreen buffers), glass sampling pinned to its resting spot (no per-frame re-record/re-sample), background darkened in the same bitmap pass via a cached colour filter.
- Scrim only covers the strip above/beside the drawer; open 320 ms (emphasized decelerate), close 200 ms.
- Static home background (wallpaper, scrims, clock, cards) is one cached hardware layer.
- Ambient/bubble fades use ModulateAlpha (no offscreen). Brushes/outlines cached via drawWithCache (no per-frame allocations).
- Banners/icons decoded at exact on-screen pixel size for the TV's density and pre-uploaded with prepareToDraw.

## v4 (versionCode 5, "4.0")
- Settings → **Update launcher**: starts the PIN-protected LAN server (only while the screen is open, private-network clients only, locks after 15 bad PINs). Scan the QR on the phone, pick an .apk (Drive/Downloads).
- Phone page: upload progress bar, then live TV status (verifying → allow installs → confirm on TV → done / error with reason).
- Checks before install: package == com.peyton.tvlauncher, same signing cert (SHA-256), versionCode higher than installed.
- Install: PackageInstaller session (create/commit on main, byte copy on IO, abandoned on failure). First time: "Allow installs first" deep-links to the per-app Unknown-sources page; on return the install starts automatically. The TV shows the system confirm; the launcher is restarted on the new version and shows a toast.
- Settings title shows the current version ("4.0 (build 5)").
- Drawer: Offscreen layer only while sliding + focus visuals snapped during the slide (no added delay). All v3.1 smoothness work retained.
- Fixed a race where the screen effect and another start call could open two LAN servers (photo + update).
- Tests: UpdaterE2ETest drives MainActivity with local testapks/ (v4.0, dummy v4.1, v4.1 other-key; not shipped).

## v4.1 (versionCode 6)
Install through the v4 updater: Settings → Update launcher → scan the QR with the phone → pick `TV Launcher v4.1.apk`.

- **Faster phone uploads**: the phone resizes to exactly what the TV needs (landscape 2560 wide, portrait 2160 tall, JPEG 0.85) in Web Workers/OffscreenCanvas, sends 5 at a time, shows "X of N", per-photo status and retries. The TV stores phone JPEGs without re-encoding; thumbnails and analysis happen in a background worker. The upload server keeps going for a moment after you leave the screen so a batch can finish.
- **Better fit**: face detection (android.media.FaceDetector) + sharpness at ingest, saved once per photo. Face-aware 16:9 crops, Ken Burns toward faces, portraits paired side by side, single portraits over a blurred copy, tiny/blurry photos skipped. Optional auto-enhance.
- **Edit dock**: hold OK on a dock icon → Edit dock, the pencil at the end of the dock, or Settings. OK picks up / drops, ◀ ▶ move, ▼ then OK (or MENU) removes, ↑ undoes, "+ Add apps" opens a checklist, BACK/Done exits. Up to 24 apps.
- **Home layouts**: Glass dock, Cinematic, Side rail, Minimal (Settings → Home layout).
- **Home cards**: Settings → Home cards (weather, 3-day, inputs, date, recent apps, photo of the day, quick buttons, countdown, note).
- **Clock style**: 6 styles, 6 bundled OFL fonts, weights; optional on the ambient screen.

Recent-apps card with the system's real history (optional, otherwise it uses apps opened from the launcher):

    adb shell appops set com.peyton.tvlauncher GET_USAGE_STATS allow

## v4.2 (versionCode 7) — no dock pop-in at startup
Install through the updater: Settings → Update launcher → pick `TV Launcher v4.2.apk`.

- **Dock is complete on the first frame.** The dock (labels, tints and the exact tile/banner bitmaps) is snapshotted to app storage
  (`files/dock_v1/`) and read synchronously in onCreate, so a cold start no longer shows an empty dock with only "All apps" while
  every installed app's icon is decoded. The live PackageManager load swaps in pixel-identical entries: nothing moves.
- The last frosted backdrop is cached too (`files/backdrop_v1.png`): glass cards and the dock have their real tint from the first
  frame, and the sharp wallpaper crossfades in over its blurred copy instead of over black. Wallpaper loading no longer waits for the
  photo backfill scan.
- Returning home: app reloads reuse unchanged entries (same activity, label and update time), so no icons are re-decoded and an
  unchanged list causes no recomposition.
- Very first run (no snapshot yet): the whole dock fades in as one unit when apps are ready, never "All apps" alone.
- Renders: `screens/v42/` (before: `screens/v42/before/`).
