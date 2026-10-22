#!/usr/bin/env bash
# Read-only: saves the TV's model + full package list so the debloat list can be tailored. Changes nothing.
#   TV=192.168.x.x ./inventory.sh
cd "$(dirname "$0")"; . ./_common.sh
OUT="tv-inventory-$(date +%Y%m%d-%H%M).txt"
{
  echo "# $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model)  Android $(sh_ getprop ro.build.version.release)  build $(sh_ getprop ro.build.display.id)"
  echo "# default home: $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
  echo "# screensaver: $(sh_ settings get secure screensaver_components)"
  echo; echo "## Hisense / VIDAA / MediaTek / partner packages"; sh_ pm list packages | sed 's/^package://' | grep -iE 'hisense|vidaa|jamdeo|\.vt\.|mediatek|mtk|samba|pluto|tubi|xumo|vudu|sling|fubo|amazon|netflix|hulu|disney|peacock|paramount|spotify|apple' | sort
  echo; echo "## Disabled already"; sh_ pm list packages -d | sed 's/^package://' | sort
  echo; echo "## All packages (with APK path)"; sh_ pm list packages -f | sed 's/^package://' | sort
} > "$OUT"
echo "Saved $OUT  — send this file back so the debloat list can be matched to your exact TV."
