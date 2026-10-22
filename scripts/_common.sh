# Shared helpers. Set TV=192.168.x.x (or TV=192.168.x.x:5555) before running, or pass -s yourself via ADB.
ADB="${ADB:-adb}"
if [ -n "$TV" ]; then
  case "$TV" in *:*) T="$TV" ;; *) T="$TV:5555" ;; esac
  $ADB connect "$T" >/dev/null 2>&1
  ADB="$ADB -s $T"
fi
sh_() { $ADB shell "$@" 2>&1 | tr -d '\r'; }
installed() { sh_ pm list packages "$1" | grep -qx "package:$1"; }
# Never touch these: boot loops, dead remote, lost HDMI inputs, no settings, no Play Store.
NEVER_RE='^(android|com\.android\.(systemui|settings|tv\.settings|tv|providers\..*|location\.fused|shell|packageinstaller|permissioncontroller|inputdevices|bluetooth|externalstorage)|com\.google\.android\.(gms|gsf|webview|tv\.remote\.service|inputmethod\.latin|leanback\.ime|packageinstaller|permissioncontroller|tvsetup|apps\.mediashell)|com\.android\.vending|com\.mediatek\.(tis|dtv.*|wwtv.*)|com\.jamdeo\.tv\.vod)$|tvinput|hdmi|source|globalkey|keyhandler|remote|bluetooth|pair|tiletransition|prism|systemui|framework|provider|webview'
