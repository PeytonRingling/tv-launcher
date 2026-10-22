#!/usr/bin/env bash
# Optional: use TV Launcher Ambient as the system screensaver (shows over any idle app, not just the home screen).
# Google TV hides third-party screensavers in Settings, so this uses adb.
#   TV=192.168.x.x ./ambient-screensaver.sh on   |   ./ambient-screensaver.sh off
cd "$(dirname "$0")"; . ./_common.sh
if [ "$1" = "off" ]; then
  PREV=$(cat screensaver-backup.txt 2>/dev/null); [ -z "$PREV" ] && PREV=com.google.android.apps.tv.dreamx/.service.Backdrop
  sh_ settings put secure screensaver_components "$PREV"; echo "Screensaver restored to $PREV"
else
  sh_ settings get secure screensaver_components > screensaver-backup.txt
  sh_ settings put secure screensaver_components com.peyton.tvlauncher/.AmbientDream
  sh_ settings put secure screensaver_enabled 1
  echo "TV Launcher Ambient is now the screensaver (previous saved in screensaver-backup.txt)."
fi
