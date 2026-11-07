#!/bin/bash
# A rehearsal of the athan on an Android emulator, with a stand-in film app (fakeplayer/) playing.
# Checks the three things that matter:
#   1. whatever is playing stops when the athan comes;
#   2. nothing gets out of the athan (keys, Home, starting the film again, a wrong code),
#      only the code; and
#   3. afterwards the film carries on by itself, at full sound, and the TV's volume is as it was.
# Run by .github/workflows/e2e.yml; writes its report and screenshots to the folder given.
# The code used here is a test code (24680), set through the settings, never eTV's built-in one.

OUT="${1:-e2e-out}"
mkdir -p "$OUT"
ETV_APK=app/build/outputs/apk/release/app-release.apk
FAKE_APK=fakeplayer/build/outputs/apk/debug/fakeplayer-debug.apk
ETV=app.etv.launcher
FAKE=app.etv.fakeplayer
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

# ---- what the film app is doing (it logs once a second) ----
status() { adb logcat -d -s FakePlayer:I | grep STATUS | tail -1 | sed 's/.*STATUS //'; }
headpos() { status | sed 's/.*head=//'; }
film_playing() {
  local a b s
  a=$(headpos); sleep 2.5; b=$(headpos); s=$(status)
  echo "      film: $s (head $a -> $b)" >> "$REPORT"
  [[ "$s" == *"playing=true volume=1.0 "* ]] && [ -n "$a" ] && [ -n "$b" ] && [ "$b" -gt "$a" ]
}
film_quiet() {
  local a b s
  a=$(headpos); sleep 2.5; b=$(headpos); s=$(status)
  echo "      film: $s (head $a -> $b)" >> "$REPORT"
  [[ "$s" == *"playing=false"* ]] && [ "$a" = "$b" ]
}
# Plays again within this many seconds (eTV asks at 1.2 s, 3 s and 6 s after the athan).
film_carries_on() {
  local i
  for i in $(seq 1 6); do film_playing && return 0; done
  return 1
}

# ---- what eTV is doing ----
resumed() { sh_ dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' ; }
athan_on_top() { local r; r=$(resumed); echo "      top: $r" >> "$REPORT"; [[ "$r" == *"$ETV/.AthanActivity"* ]]; }
athan_gone() { local r; r=$(resumed); echo "      top: $r" >> "$REPORT"; [[ "$r" != *"AthanActivity"* ]]; }
film_on_top() { [[ "$(resumed)" == *"$FAKE/"* ]]; }
ETV_UID=""
# eTV's own player is sounding (Android's list of players: started, belonging to eTV).
athan_sounding() {
  local p; p=$(sh_ dumpsys audio | grep "u/pid:$ETV_UID/" | grep 'state:started')
  echo "      eTV players started: $(echo "$p" | grep -c started)" >> "$REPORT"
  [ -n "$p" ]
}
athan_silent() { ! athan_sounding; }
athan_stays() { athan_on_top && athan_sounding; }
volume_block() { sh_ dumpsys audio | sed -n '/- STREAM_MUSIC:/,/Devices:/p' | grep -E 'Muted|Current|Indexes|Devices' ; }
music_volume() { volume_block | tr '\n' ' '; }
service_running() { sh_ dumpsys activity services "$ETV" | grep -q 'AthanService'; }

press() { adb shell input keyevent "$@"; sleep 2.5; }

DAY=500000
fire_athan() {
  DAY=$((DAY + 1))
  local now; now="$(sh_ date +%s)000"
  # Exactly what the alarm sends at Dhuhr (prayer 2).
  adb shell am broadcast -n "$ETV/.AthanReceiver" -a app.etv.launcher.ATHAN_ALARM \
    --ei prayer 2 --ei day "$DAY" --el at "$now" >> "$OUT/log.txt" 2>&1
}

start_film() {
  adb shell am force-stop "$FAKE"
  adb shell am start -W -n "$FAKE/.PlayerActivity" >> "$OUT/log.txt" 2>&1
  sleep 4
}

# =====================================================================
say "== Setting up"
adb install -r -g "$ETV_APK" >> "$OUT/log.txt" 2>&1
adb install -r -g "$FAKE_APK" >> "$OUT/log.txt" 2>&1
ETV_UID=$(sh_ dumpsys package "$ETV" | grep -m1 -o 'userId=[0-9]*' | cut -d= -f2)
say "  eTV uid $ETV_UID, Android $(sh_ getprop ro.build.version.release)"
# As make-default.sh does on the TV: Now playing access and drawing over other apps.
adb shell cmd notification allow_listener "$ETV/$ETV.MediaListener" >> "$OUT/log.txt" 2>&1
adb shell appops set "$ETV" SYSTEM_ALERT_WINDOW allow >> "$OUT/log.txt" 2>&1
adb shell cmd package set-home-activity "$ETV/.MainActivity" >> "$OUT/log.txt" 2>&1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP

# eTV's settings: athan on, pause and carry on, athan at 60 % volume, and the test code.
adb shell am force-stop "$ETV"
cat > "$OUT/etv.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="setupShown" value="true" />
    <boolean name="athanSetupShown" value="true" />
    <boolean name="athanOn" value="true" />
    <boolean name="athanPause" value="true" />
    <boolean name="athanResume" value="true" />
    <int name="athanVolume" value="60" />
    <string name="athanCodeHash">28d80a9f800488718ccc3afae5106766dc71ab84b9fcacd3cf739bda5b97a764</string>
    <string name="athanCodeSalt">00112233445566778899aabbccddeeff</string>
    <int name="athanCodeLength" value="5" />
    <int name="athanCodeRounds" value="60000" />
</map>
EOF
adb shell run-as "$ETV" mkdir -p shared_prefs
adb shell "run-as $ETV sh -c 'cat > shared_prefs/etv.xml'" < "$OUT/etv.xml"
sh_ run-as "$ETV" cat shared_prefs/etv.xml | grep -q athanCodeHash && say "  settings written" || say "  settings NOT written"
adb shell am start -W -n "$ETV/.MainActivity" >> "$OUT/log.txt" 2>&1
sleep 6
shot 00-home
# Fetch the dua now, as the TV would have done long before (so the full run has it).
sleep 20

# The TV's volume: a middle setting, not muted.
adb shell media volume --stream 3 --set 9 >> "$OUT/log.txt" 2>&1 || adb shell cmd media_session volume --stream 3 --set 9 >> "$OUT/log.txt" 2>&1

# =====================================================================
say ""
say "== 1. A film is playing and the athan comes; ended with the code"
adb logcat -c
start_film
shot 10-film
check "the film plays with sound before the athan" film_playing
VOL_BEFORE=$(music_volume)
say "      volume before: $VOL_BEFORE"

fire_athan
sleep 4
shot 11-athan
check "the film stopped" film_quiet
check "the athan screen is on top" athan_on_top
check "the athan is sounding" athan_sounding

say "  -- trying to get out"
for k in KEYCODE_BACK KEYCODE_DPAD_CENTER KEYCODE_ENTER KEYCODE_ESCAPE KEYCODE_MEDIA_STOP KEYCODE_MEDIA_PLAY_PAUSE KEYCODE_MEDIA_PLAY KEYCODE_MEDIA_NEXT KEYCODE_DPAD_LEFT KEYCODE_GUIDE KEYCODE_SETTINGS; do
  press "$k"
  check "$k: the athan stays" athan_stays
done
check "after all those keys the film is still quiet" film_quiet
press KEYCODE_HOME
sleep 1
shot 12-after-home
check "Home: the athan screen is back on top" athan_on_top
check "Home: the athan still sounds" athan_sounding

adb shell am start -n "$FAKE/.PlayerActivity" --ez play true >> "$OUT/log.txt" 2>&1
sleep 4
shot 13-after-film-started
check "starting the film again: the athan screen is back on top" athan_on_top
check "starting the film again: the film is paused again" film_quiet
check "starting the film again: the athan still sounds" athan_sounding

adb shell input keyevent KEYCODE_1 KEYCODE_3 KEYCODE_5 KEYCODE_7 KEYCODE_9
sleep 4
check "a wrong code: the athan carries on" athan_on_top
check "a wrong code: still sounding" athan_sounding
adb shell input keyevent KEYCODE_2 KEYCODE_4 KEYCODE_6 KEYCODE_8
sleep 3
check "four digits of the code: still on" athan_on_top

# The code, after some stray digits (only the last digits count).
adb shell input keyevent KEYCODE_9 KEYCODE_2 KEYCODE_4 KEYCODE_6 KEYCODE_8 KEYCODE_0
sleep 5
shot 14-after-code
check "the code ends the athan screen" athan_gone
check "the code silences the athan" athan_silent
check "the film carries on by itself, at full sound" film_carries_on
check "the film is in front again" film_on_top
VOL_AFTER=$(music_volume)
say "      volume after:  $VOL_AFTER"
check "the TV's volume (and mute) are exactly as before" test "$VOL_BEFORE" = "$VOL_AFTER"
sleep 8
check "still playing a while later" film_playing
shot 15-film-again

# =====================================================================
say ""
say "== 2. The whole athan and dua, untouched"
adb logcat -c
start_film
check "the film plays before the athan" film_playing
VOL_BEFORE=$(music_volume)
fire_athan
sleep 4
check "the film stopped" film_quiet
check "the athan screen is on top" athan_on_top
START=$(date +%s)
N=0
while [[ "$(resumed)" == *AthanActivity* ]]; do
  T=$(( $(date +%s) - START ))
  if [ $((N % 6)) -eq 0 ]; then
    shot "$(printf '2%02d-at-%03ds' $((N / 6)) "$T")"
    S=$(status)
    say "      ${T}s: film $S; eTV sounding: $(athan_sounding && echo yes || echo no)"
    [[ "$S" == *"playing=true"* ]] && { FAIL=$((FAIL + 1)); say "  FAIL  the film played during the athan at ${T}s"; }
  fi
  N=$((N + 1))
  [ "$T" -gt 900 ] && break
  sleep 5
done
T=$(( $(date +%s) - START ))
say "      the athan screen closed after ${T}s"
check "the athan and dua ran their whole length (over 3 minutes)" test "$T" -gt 190
check "the athan screen closed by itself" athan_gone
check "eTV is silent" athan_silent
check "the film carries on by itself, at full sound" film_carries_on
check "the film is in front again" film_on_top
VOL_AFTER=$(music_volume)
say "      volume before: $VOL_BEFORE"
say "      volume after:  $VOL_AFTER"
check "the TV's volume (and mute) are exactly as before" test "$VOL_BEFORE" = "$VOL_AFTER"
sleep 8
check "still playing a while later" film_playing
shot 29-film-again

# =====================================================================
say ""
say "== 3. Nothing playing, athan ended with the code: nothing starts afterwards"
adb shell am force-stop "$FAKE"
adb shell input keyevent KEYCODE_HOME
sleep 3
adb logcat -c
fire_athan
sleep 5
check "the athan screen is on top" athan_on_top
adb shell input keyevent KEYCODE_2 KEYCODE_4 KEYCODE_6 KEYCODE_8 KEYCODE_0
sleep 9
check "the code ends it" athan_gone
check "the film app didn't start by itself" bash -c "! adb shell pidof $FAKE >/dev/null"

adb logcat -d > "$OUT/logcat.txt"
say ""
say "== $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
