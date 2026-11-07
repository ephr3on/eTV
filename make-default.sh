#!/bin/bash
# Makes eTV the Home app on your TV and turns on the eTV Home button helper.
# Usage:  bash make-default.sh <TV-IP>
#         bash make-default.sh <TV-IP> --replace  (also switch the TV's own home screen off: no flash on Home)
#         bash make-default.sh <TV-IP> --undo     (give Home back to the TV's own launcher)
set -e

TV="$1"
MODE="$2"
if [ -z "$TV" ]; then
  echo "Usage: bash make-default.sh <TV-IP> [--replace | --undo]"
  exit 1
fi
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  echo "adb not found. Install it with: brew install android-platform-tools"
  exit 1
fi

"$ADB" connect "$TV" >/dev/null || true
for i in $(seq 1 30); do
  STATE="$("$ADB" -s "$TV" get-state 2>/dev/null || true)"
  [ "$STATE" = "device" ] && break
  sleep 2
done
if [ "$STATE" != "device" ]; then
  echo "Couldn't reach the TV at $TV. Check debugging is on and accept the prompt on the TV."
  exit 1
fi
tv() { "$ADB" -s "$TV" shell "$@" | tr -d '\r'; }

if ! tv pm list packages app.etv.launcher | grep -q "app.etv.launcher"; then
  echo "eTV isn't installed on the TV yet. Install the APK first."
  exit 1
fi

SVC="app.etv.launcher/app.etv.launcher.HomeButtonService"
# The TV's own home screens: Android TV (Philips, Sony, ...) and Google TV.
STOCK="com.google.android.tvlauncher com.google.android.apps.tv.launcherx"
CUR="$(tv settings get secure enabled_accessibility_services)"
[ "$CUR" = "null" ] && CUR=""

if [ "$MODE" = "--undo" ]; then
  echo "==> Switching the TV's own home screen back on"
  for P in $STOCK; do tv pm enable "$P" >/dev/null 2>&1 || true; done
  echo "==> Turning the eTV Home button helper off"
  NEW="$(echo "$CUR" | tr ':' '\n' | grep -v "^$SVC\$" | paste -sd: -)"
  if [ -z "$NEW" ]; then
    tv settings delete secure enabled_accessibility_services >/dev/null
  else
    tv settings put secure enabled_accessibility_services "$NEW"
  fi
  echo "==> Done. To pick another Home app, uninstall eTV or choose one when the TV asks."
  exit 0
fi

echo "==> Setting eTV as the Home app"
OUT="$(tv cmd package set-home-activity app.etv.launcher/.MainActivity 2>&1 || true)"
if echo "$OUT" | grep -qi "success"; then
  echo "    done"
else
  echo "    this TV doesn't accept that; the helper below covers it"
fi

echo "==> Turning on the eTV Home button helper"
if echo "$CUR" | tr ':' '\n' | grep -q "^$SVC\$"; then
  NEW="$CUR"
elif [ -z "$CUR" ]; then
  NEW="$SVC"
else
  NEW="$CUR:$SVC"
fi
tv settings put secure enabled_accessibility_services "$NEW"
tv settings put secure accessibility_enabled 1

if [ "$MODE" = "--replace" ]; then
  echo "==> Switching the TV's own home screen off (undo any time with --undo)"
  for P in $STOCK; do
    if tv pm list packages "$P" | grep -q "package:$P\$"; then
      tv pm disable-user --user 0 "$P" >/dev/null && echo "    $P is off"
    fi
  done
fi

echo "==> Letting eTV show what's playing (Now playing card)"
tv cmd notification allow_listener app.etv.launcher/app.etv.launcher.MediaListener >/dev/null 2>&1 \
  && echo "    done" || echo "    this TV doesn't accept that; see eTV > Settings > Now playing"

echo "==> Letting the athan screen come up over any app"
tv appops set app.etv.launcher SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 \
  && echo "    done" || echo "    this TV doesn't accept that; the athan still plays, its screen may not show"

echo "==> Pressing Home to check"
tv input keyevent KEYCODE_HOME
echo "==> Done. Press Home on the remote: it should open eTV."
echo "    If it doesn't, open eTV > Settings > Home button > Teach eTV your TV's home screen."
