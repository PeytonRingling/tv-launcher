#!/usr/bin/env bash
# Hisense Google TV debloat — DISABLES packages for user 0 (pm disable-user). Nothing is uninstalled.
# Every change is logged to debloat-log.txt; ./restore.sh turns everything back on. A factory reset also undoes it.
#
#   TV=192.168.x.x ./debloat.sh              # SAFE tier only (ads, telemetry, store demo)
#   TV=192.168.x.x ./debloat.sh --optional   # + OPTIONAL tier (preinstalled apps / features you may not use)
#   TV=192.168.x.x ./debloat.sh --optional --risky   # + RISKY tier (read the notes first!)
#   ./debloat.sh --dry-run ...                # show what would happen, change nothing
#
# Package names that aren't on your TV are skipped automatically, so unverified names are harmless.
# "(verify)" = reported on Hisense Android/Google TV firmware but not confirmed on your exact model yet.
# Run ./inventory.sh first and send the result so this list can be tightened for your model.
cd "$(dirname "$0")"; . ./_common.sh
OPT=0; RISKY=0; DRY=0
for a in "$@"; do case "$a" in --optional) OPT=1;; --risky) RISKY=1;; --dry-run) DRY=1;; esac; done

# tier | package | what you lose
LIST=$(cat <<'PKGS'
SAFE|com.google.android.tvrecommendations|Android TV recommendation/ad rows feed (may not exist on Google TV)
SAFE|com.google.android.partnersetup|Partner/OEM promo + usage statistics setup
SAFE|com.google.android.feedback|Google crash/feedback reporter
SAFE|com.android.tv.feedbackconsent|Android TV feedback consent prompt
SAFE|com.google.android.tv.bugreportsender|Bug-report uploader
SAFE|tv.samba.ssm|Samba TV ad tracking / content recognition (if present)
SAFE|com.hisense.hireport|(verify) Hisense usage reporting / telemetry
SAFE|com.hisense.hitv.das|(verify) Hisense data-analytics service
SAFE|com.hisense.service.ad|(verify) Hisense ad service
SAFE|com.hisense.business.ad|(verify) Hisense ad service
SAFE|com.hisense.tv.advertisementproxy|(verify) Hisense ad proxy
SAFE|com.hisense.magic.ad|(verify) Hisense ad module
SAFE|com.hisense.demonstrate|(verify) Store demo / feature showcase
SAFE|com.hisense.electronictutorial|(verify) Hisense tutorial / app guide
SAFE|com.android.printspooler|Printing (useless on a TV)
OPTIONAL|com.google.android.videos|Google TV "Shop"/Movies app (purchases still playable on other devices)
OPTIONAL|com.google.android.play.games|Play Games
OPTIONAL|com.google.android.youtube.tvmusic|YouTube Music
OPTIONAL|com.google.android.youtube.tvkids|YouTube Kids (keep if your kids use it!)
OPTIONAL|com.google.android.apps.tv.dreamx|Google Ambient Mode screensaver — ALSO hosts the Google Home smart-home panel; only disable if you use TV Launcher Ambient
OPTIONAL|com.android.dreams.basic|Basic Android screensavers (colors/clock)
OPTIONAL|com.mediatek.AirplayAPK|(verify) AirPlay receiver — lose AirPlay from iPhone/Mac
OPTIONAL|com.mediatek.airplaydaemon|(verify) AirPlay background service
OPTIONAL|com.mediatek.partner.airplay_sys|(verify) AirPlay system part
OPTIONAL|com.android.adservices.api|Privacy Sandbox ad-measurement (may refuse on Android 14)
OPTIONAL|com.android.ondevicepersonalization.services|On-device ad personalization
OPTIONAL|com.android.federatedcompute.services|Federated learning
# Partner/preinstalled streaming apps: remove the leading '#' for any you never use.
#OPTIONAL|com.amazon.amazonvideo.livingroom|Prime Video (its remote button will stop working)
#OPTIONAL|tv.pluto.android|Pluto TV
#OPTIONAL|com.tubitv|Tubi
#OPTIONAL|com.xumo.xumo.tv|Xumo Play
#OPTIONAL|air.com.vudu.air.DownloaderTablet|Fandango at Home (Vudu)
#OPTIONAL|com.sling|Sling TV
#OPTIONAL|com.peacocktv.peacockandroid|Peacock
#OPTIONAL|com.netflix.ninja|Netflix (its remote button will stop working)
#OPTIONAL|com.google.android.youtube.tv|YouTube (its remote button will stop working)
RISKY|com.google.android.katniss|Google Assistant — the remote's mic/voice search stops working
RISKY|com.hisense.tv.ota|(verify) Hisense firmware updates — you stop getting security/bug fixes
RISKY|com.vt.tv.ota|Hisense Google TV firmware-update app (confirmed on U8K/U8N) — stops updates and the forced update prompt
PKGS
)

LOG=debloat-log.txt
echo "# $(date)  $(sh_ getprop ro.product.model)" >> "$LOG"
n=0
while IFS='|' read -r tier pkg why; do
  case "$tier" in ''|\#*) continue;; esac
  [ "$tier" = OPTIONAL ] && [ $OPT = 0 ] && continue
  [ "$tier" = RISKY ] && [ $RISKY = 0 ] && continue
  if echo "$pkg" | grep -qE "$NEVER_RE"; then echo "REFUSED  $pkg (protected)"; continue; fi
  if ! installed "$pkg"; then echo "skip     $pkg (not on this TV)"; continue; fi
  if [ $DRY = 1 ]; then echo "would    $tier  $pkg — $why"; continue; fi
  r=$(sh_ pm disable-user --user 0 "$pkg")
  echo "$r" | grep -q "disabled" && { echo "disabled $tier  $pkg — $why"; echo "$pkg" >> "$LOG"; n=$((n+1)); } || echo "FAILED   $pkg: $r"
done <<< "$LIST"
echo "Done: $n package(s) disabled. Undo any time with ./restore.sh  (or one: adb shell pm enable <pkg>)"
