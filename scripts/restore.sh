#!/usr/bin/env bash
# Re-enables everything debloat.sh disabled (reads debloat-log.txt), or the packages you name.
#   TV=192.168.x.x ./restore.sh                 # undo everything in debloat-log.txt
#   TV=192.168.x.x ./restore.sh com.vt.tv.ota   # undo just one
#   TV=192.168.x.x ./restore.sh --all-disabled  # re-enable EVERY disabled package on the TV
cd "$(dirname "$0")"; . ./_common.sh
if [ "$1" = "--all-disabled" ]; then PKGS=$(sh_ pm list packages -d | sed 's/^package://')
elif [ $# -gt 0 ]; then PKGS="$*"
else PKGS=$(grep -v '^#' debloat-log.txt 2>/dev/null | sort -u); fi
[ -z "$PKGS" ] && { echo "Nothing to restore."; exit 0; }
for p in $PKGS; do echo "$(sh_ pm enable "$p" | tail -1)"; done
echo "Restored. A reboot (adb reboot) makes sure everything restarts cleanly."
