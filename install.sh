#!/bin/bash
# Builds eTV and installs it on your Android TV over Wi-Fi.
# Usage:  bash install.sh <TV-IP>          e.g.  bash install.sh 192.168.178.45
#         bash install.sh <TV-IP>:<port>   if your TV shows a different port
set -e
cd "$(dirname "$0")"

TV="$1"
if [ -z "$TV" ]; then
  echo "Usage: bash install.sh <TV-IP>   (find it on the TV: Settings > Network & Internet > your Wi-Fi)"
  exit 1
fi
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac

# Android SDK and adb (Android Studio's default location on a Mac)
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ ! -d "$SDK" ]; then
  echo "Android SDK not found at $SDK."
  echo "Install Android Studio once (https://developer.android.com/studio), open it, let it finish setup, then run this again."
  exit 1
fi
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  echo "adb not found. In Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools > Android SDK Platform-Tools."
  exit 1
fi
echo "sdk.dir=$SDK" > local.properties

# Java: use the JDK bundled with Android Studio if none is set
if [ -z "$JAVA_HOME" ]; then
  for J in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -d "$J" ] && export JAVA_HOME="$J" && break
  done
fi

if [ -f app/etv-release.jks ] && [ -z "$ETV_KEYSTORE_PASSWORD" ]; then
  read -r -s -p "Password for app/etv-release.jks (Enter to skip): " ETV_KEYSTORE_PASSWORD; echo
  export ETV_KEYSTORE_PASSWORD
fi

echo "==> Building eTV (the first build downloads Gradle and takes a few minutes)"
chmod +x gradlew
./gradlew --no-daemon -q assembleRelease
APK="app/build/outputs/apk/release/app-release.apk"

echo "==> Connecting to the TV at $TV"
echo "    If the TV asks \"Allow debugging?\", tick \"Always allow\" and choose Allow."
"$ADB" connect "$TV"
for i in $(seq 1 30); do
  STATE="$("$ADB" -s "$TV" get-state 2>/dev/null || true)"
  [ "$STATE" = "device" ] && break
  sleep 2
done
if [ "$STATE" != "device" ]; then
  echo "Couldn't reach the TV. Check that it's on the same Wi-Fi, network/USB debugging is on, and you allowed the prompt."
  exit 1
fi

echo "==> Installing"
if ! OUT="$("$ADB" -s "$TV" install -r "$APK" 2>&1)"; then
  if echo "$OUT" | grep -q "UPDATE_INCOMPATIBLE\|signatures do not match"; then
    # An older eTV build was signed with a different key: replace it once.
    echo "    replacing an older eTV build (your eTV settings start fresh this once)"
    "$ADB" -s "$TV" uninstall app.etv.launcher >/dev/null
    "$ADB" -s "$TV" install "$APK"
  else
    echo "$OUT"
    exit 1
  fi
fi

bash "$(dirname "$0")/make-default.sh" "$1"
