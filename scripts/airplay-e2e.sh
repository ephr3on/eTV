#!/bin/bash
# AirPlay on an Android emulator, without an Apple device: does the receiver start with eTV, in its
# own process; does it answer an AirPlay "info" request like an iPhone's first one; does it announce
# itself over Bonjour; does Customise show it; and does the home screen survive the receiver dying
# (and bring it back)? Run by .github/workflows/e2e.yml after the athan rehearsal; writes its report
# and screenshots to the folder given.

OUT="${1:-e2e-out/airplay}"
mkdir -p "$OUT"
ETV_APK=app/build/outputs/apk/release/app-release.apk
ETV=app.etv.launcher
PASS=0
FAIL=0
REPORT="$OUT/report.txt"
: > "$REPORT"

say() { echo "$*" | tee -a "$REPORT"; }
check() {
  local what="$1"; shift
  if "$@"; then PASS=$((PASS + 1)); say "  PASS  $what"; else FAIL=$((FAIL + 1)); say "  FAIL  $what"; fi
}
sh_() { adb shell "$@" | tr -d '\r'; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }

home_pid() { sh_ pidof "$ETV" | awk '{print $1}'; }
airplay_pid() { sh_ pidof "$ETV:airplay" | awk '{print $1}'; }
service_running() { sh_ dumpsys activity services "$ETV" | grep -q 'AirPlayService'; }
receiver_process() { [ -n "$(airplay_pid)" ]; }
own_process() { local h a; h=$(home_pid); a=$(airplay_pid); [ -n "$h" ] && [ -n "$a" ] && [ "$h" != "$a" ]; }
started_log() { adb logcat -d | grep -q 'AirPlay server started on port\|Server started on port'; }
bonjour_log() { adb logcat -d -s NsdServiceManager:* | grep -qi 'registered'; }

# What an iPhone asks first: GET /info over RTSP. The answer is a binary plist describing the TV.
info_answer() {
  adb forward tcp:17000 tcp:7000 >> "$OUT/log.txt" 2>&1
  python3 - "$OUT/info.bin" <<'EOF'
import socket, sys
s = socket.create_connection(("127.0.0.1", 17000), timeout=10)
s.sendall(b"GET /info RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/690.7.1\r\n\r\n")
data = b""
try:
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        data += chunk
        head, _, body = data.partition(b"\r\n\r\n")
        if b"\r\n\r\n" in data:
            length = 0
            for line in head.split(b"\r\n"):
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":", 1)[1])
            if len(body) >= length:
                break
except socket.timeout:
    pass
s.close()
open(sys.argv[1], "wb").write(data)
EOF
  local first; first=$(head -c 40 "$OUT/info.bin" | head -1 | tr -d '\r')
  echo "      answer: $first ($(wc -c < "$OUT/info.bin") bytes)" >> "$REPORT"
  [[ "$first" == "RTSP/1.0 200"* ]] && grep -aq 'features' "$OUT/info.bin" && grep -aq 'deviceID' "$OUT/info.bin"
}

say "== AirPlay"
adb install -r -g "$ETV_APK" >> "$OUT/log.txt" 2>&1
adb shell input keyevent KEYCODE_WAKEUP
adb logcat -c
adb shell am force-stop "$ETV"
adb shell am start -W -n "$ETV/.MainActivity" >> "$OUT/log.txt" 2>&1
sleep 8
shot 00-home
check "the receiver is running" service_running
check "in its own process, apart from the home screen" own_process
check "the AirPlay server started" started_log
check "it announced itself over Bonjour" bonjour_log
check "it answers an iPhone's first request (GET /info)" info_answer
sleep 3
check "the AirPlay screen didn't open just for that" bash -c "! adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | grep -q AirPlayActivity"

say "  -- Customise -> AirPlay"
adb shell input keyevent KEYCODE_PROG_BLUE
sleep 3
adb shell input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
sleep 2
shot 10-customise-airplay
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 1
shot 11-customise-airplay-options
check "Customise shows AirPlay as ready" bash -c "adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -q 'Ready: your Apple devices see'"
adb shell input keyevent KEYCODE_BACK KEYCODE_BACK
sleep 2

say "  -- the receiver dies: the home screen stays"
HOME_BEFORE=$(home_pid)
AP=$(airplay_pid)
adb shell run-as "$ETV" kill -9 "$AP" >> "$OUT/log.txt" 2>&1 || adb shell kill -9 "$AP" >> "$OUT/log.txt" 2>&1
sleep 3
check "the home screen's process is untouched" test "$(home_pid)" = "$HOME_BEFORE"
adb shell input keyevent KEYCODE_HOME
sleep 6
shot 20-home-after
check "Home brings the receiver back" receiver_process
sleep 2
check "and it answers again" info_answer

adb logcat -d > "$OUT/logcat.txt"
say ""
say "== $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
