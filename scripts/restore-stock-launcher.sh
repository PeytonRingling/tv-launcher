#!/usr/bin/env bash
# Puts the Google TV home screen back exactly as it was.
cd "$(dirname "$0")"; . ./_common.sh
sh_ pm enable com.google.android.apps.tv.launcherx
sh_ pm enable com.google.android.tungsten.setupwraith
sh_ cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
sh_ input keyevent KEYCODE_HOME
echo "Google TV home restored. If Home still opens TV Launcher, uninstall it or run: adb reboot"
