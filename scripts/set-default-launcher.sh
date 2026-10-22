#!/usr/bin/env bash
# Makes TV Launcher the Home screen on a (Hisense) Google TV.
#   TV=192.168.x.x ./set-default-launcher.sh                  # soft: set as home activity only
#   TV=192.168.x.x ./set-default-launcher.sh --disable-stock  # also disable Google TV's launcher (most reliable on Hisense)
# Undo with ./restore-stock-launcher.sh
cd "$(dirname "$0")"; . ./_common.sh
ME=com.peyton.tvlauncher/.MainActivity
installed com.peyton.tvlauncher || { echo "Install TV Launcher first:  adb install -r \"TV Launcher v1.apk\""; exit 1; }
echo "Current home: $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)" | tee -a launcher-backup.txt
sh_ cmd package set-home-activity "$ME"
if [ "$1" = "--disable-stock" ]; then
  # Order matters: our launcher must already be installed and set, or the TV has no home screen.
  sh_ pm disable-user --user 0 com.google.android.apps.tv.launcherx
  sh_ pm disable-user --user 0 com.google.android.tungsten.setupwraith   # otherwise it re-grabs HOME on Google TV
fi
sh_ input keyevent KEYCODE_HOME
echo "Press Home on the remote. If a 'Select a Home app' prompt appears pick TV Launcher → Always."
