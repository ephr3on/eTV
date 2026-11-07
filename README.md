# eTV — a calm Sidi Bou Said launcher for Android TV

A home screen with arched-door app tiles, a quiet clock and greeting, today's prayer times, and nothing that
moves on its own. No libraries, no ads, no autoplaying previews. It connects to this repository on GitHub to look
for a new version of itself (you can switch that off), and, for the athan, to the prayer-time source you choose
and the Internet Archive for the recordings you pick.

## Themes

Customise → Theme switches the whole home screen, not just its colours. Each has its own highlight colours;
your apps, home row and settings stay the same.

| Theme | What it's like |
|---|---|
| **Classic** | Today's eTV: the arched doors in a row, greeting and clock above, the dock at the bottom |
| **Doorway** | Calmer: the prayer times down one top corner and Search / Inputs / All apps / Settings as round buttons down the other, framing the greeting and clock; the selected app named under the doors, Now playing at the foot |
| **Riad** | Daylight: blue doors with brass studs on a whitewashed wall, tabs along the top, an **Inputs** row (Live TV, HDMI) on the home screen, a band of blue-and-white tiles |
| **Cartouche** | Pharaonic: lapis and gold, apps as cartouches, the clock in a cartouche, a side rail for the sections, a papyrus frieze (Reem Kufi type) |
| **Lantern** | One door at a time: the selected app large and lit in the middle, its neighbours fading away; along the top, the clock and the buttons mirror each other on either side of the prayer times, which show one at a time the same way (the next prayer large, its neighbours fading), all on one line |

The remote works the same everywhere: arrows move within the doors, buttons, inputs or Now playing, stop at the
ends of a row, and moving back into the doors returns to the app you were on.

## Using it with the remote

Made with the Philips remote in mind (works with any Android TV remote).

| On the home screen | What it does |
|---|---|
| Left / Right | Move between apps (the selected arch fills with your highlight colour). The app in the middle of the row is selected when you arrive |
| OK | Open the app |
| Hold OK, or Options / Info | Options for the selected app: Open, Move, Remove from home, App info |
| Move → Left / Right → OK | Re-order the home row |
| 1 – 9 | Open the app in that spot on the home row |
| 0 or Red | All apps |
| Green or Sources | Inputs (HDMI etc.) |
| During the athan | Nothing, until it and the dua are over (only the private code ends it early) |
| Yellow | Search |
| Blue, or Options with nothing selected | Customise |
| Down | Dock: Search, Inputs, All apps, Settings |
| Home, from another app | Back to exactly where you were: the same app on the row, dock button, or place in All apps |
| Home again (already in eTV), or Back | The app in the middle of the row |

Left / Right stop at the ends of the home row and the dock, and Up from the dock returns to the app you were on.

In **All apps**: hold OK or press Options on an app to add it to home, hide it, open its info or uninstall it.
Channel + / − (or Page Up / Down) jump a screen at a time.

**Customise** (the Settings button) changes, instantly: highlight colour (Whitewash, Sand, Bougainvillea, Sea glass),
background (Sea blue, Night, Dusk), background arches, greeting (Arabic and English / Arabic / English / off),
12- or 24-hour clock, date, arch size, app icon or letter inside each arch, when app names show, hidden apps,
and resetting the home row. It also lists the remote shortcuts and opens the Home button setup.

## Now playing

When another app plays in the background (say you press Home while a SmartTube video is running), a slim bar
fades in between the doors and the dock buttons: the artwork in a small arched window, the app
and title, the time, and round previous / play-pause / next studs. The door studs under the date light up one
by one as it plays. Press **Down** from the apps (or **Up** from the dock) to reach it, **Left / Right** to move along it (**OK** on
the artwork opens the app on its player), **Up** for the apps and **Down** for the dock.

It stays while something plays, and for 2 minutes after you pause (15 seconds after it stops), so you can
still resume from home. Then it fades away, and it disappears as soon as the app closes its player.

Android only shares what other apps are playing with apps that have *notification access*. eTV uses it for
nothing else: it ignores every notification and keeps nothing. TVs often hide that switch, so give it once
from your Mac (`make-default.sh` does this too):

```sh
adb shell cmd notification allow_listener app.etv.launcher/app.etv.launcher.MediaListener
```

Customise → Now playing shows whether it's set up, and turns the card off.

## Athan

The five prayer times (and sunrise) are on the home screen in every theme, the next prayer marked with how
long until it, and the Hijri date sits under the date. When a prayer's time comes, the athan plays:

- whatever is playing on the TV (YouTube, SmartTube, music, a film) pauses, and the athan screen comes up;
- after the athan, Sheikh al-Sha'rawi's dua after the athan (دعاء الشعراوي بعد الأذان);
- then the athan screen closes and what was playing carries on.

Nothing on the remote ends it early: OK, Back, Stop and Pause do nothing, and if Home or an app's button hides
the athan screen it comes straight back, with anything that started playing paused again. Only your own code, typed
on the number keys, ends it before the athan and the dua are over: set it on the TV in Settings → Athan → Code to
end the athan early (it stays on the TV, as a salted hash; without one the athan always plays to the end). Volume works as always.

Everything is in **Settings → Athan**:

| Option | What it does |
|---|---|
| Athan at prayer times | On / off for all of it (the times still show on the home screen) |
| City | Search for your city, or let the TV find it from its internet connection |
| Prayer times from | **aladhan.com** (worked out online for your city; the default), **a mosque** (the times it publishes on mawaqit.net: mosques near your city, or search by name), or **on the TV** (no internet) |
| Calculation method, Asr, Fajr and Isha in summer | How the times are worked out (Muslim World League, Egyptian, Umm al-Qura, Diyanet, ...) |
| Fajr athan / Athan for the other prayers | Makkah's Fajr athan and Makkah's athan by default; also Ali Mulla, Al-Aqsa, Cairo (Abdul Basit, Al-Husary, ...), Tunisia, or your own recording from a link |
| After the athan | Sheikh al-Sha'rawi's dua, your own recording from a link, or no dua |
| Each prayer | Switch one prayer's athan off, give it its own recording, move it by a few minutes, or set a fixed time |
| What's playing on the TV | Pause, then carry on (default) / Pause / Keep playing |
| Athan volume | As the TV is, or a set level (it un-mutes the TV and puts the volume back afterwards) |
| Code to end the athan early | Your own 4–12 digit code; typed on the remote's number keys during the athan, it ends it. Without one, nothing does |
| Listen now | Plays the next athan and the dua straight away (to the end, like the real one) |
| On the home screen | Prayer times, sunrise, Hijri date (and a ±day adjustment for the month's start) |
| In Lantern | One at a time (the next prayer large, its neighbours fading; the default), a line through the day, the sun's path over the lit door, or at the foot of the screen |

The times from aladhan.com or the mosque are kept on the TV, so they work without internet; whenever they're
missing, the TV works them out itself with the same method. Makkah's athan and Makkah's Fajr athan are inside eTV;
the other recordings and the dua are fetched once when you choose them and kept on the TV (until then Makkah's athan
plays). The built-in recordings are from *Azaan of World Mosques* on the Internet Archive, marked public domain.

The athan only plays while the TV is on (not in standby). To pause other apps most reliably eTV uses the same
permission as *Now playing*; to bring the athan screen up over any app it uses the Home button helper or the
"display over other apps" permission, which `make-default.sh` gives:

```sh
adb shell appops set app.etv.launcher SYSTEM_ALERT_WINDOW allow
```

## Making the Home button open eTV

Philips and many other TVs open their own home screen when you press Home, even after you choose another
one. eTV handles this three ways; any one is enough.

**1. The Home button helper (on the TV, no computer).**
The first time eTV opens, it shows the **Home button** screen (later: Settings → Home button).
Choose *Turn on the Home button helper*, pick **eTV Home button** in the Accessibility list that opens
(on Philips usually under Accessibility → Services), switch it on and confirm.

From then on, whenever the TV opens its own home screen, eTV switches straight back. It can show for a
moment first. The helper only sees which app is opening, never what's on screen or which buttons you press,
and it keeps and sends nothing. You can pause it from the same screen.

(Version 1.2 caught the Home button itself, but on Android TV that stops held Volume up / down from
repeating, so 1.3 went back to this. For no flash at all, use option 2 with `--replace`.)

If Home still lands somewhere else, choose *Teach eTV your TV's home screen* and press Home within 45 seconds.
eTV remembers that screen and switches away from it from then on.

**2. With a computer (one command).** With the TV's debugging on (see below):

```sh
bash make-default.sh <TV-IP>
```

It sets eTV as the Home app and turns the helper on. `bash make-default.sh <TV-IP> --undo` turns the helper off again.

For no flash at all: `bash make-default.sh <TV-IP> --replace` also switches the TV's own home screen off, so Home
can only go to eTV. Things that live in that screen (its recommendations and its settings panel) are gone while
it's off. `--undo` switches it back on.
`install.sh` runs this automatically after installing.

**3. If the TV asks** which app to use for Home, choose **eTV → Always**.

eTV also opens by itself when the TV starts.

## Getting the APK

Every push to `main` builds eTV and, once the signing key below is set up, publishes it as a
[GitHub release](../../releases) named `build-<number>` with `eTV.apk` attached. The TV updates itself from these
(see *Updates*). Builds of other branches are under the **Actions** tab, as the **eTV-apk** artifact.

Or open this folder in Android Studio and run `./gradlew assembleRelease`
(the APK is in `app/build/outputs/apk/release/`).

### The signing key

Builds are signed with one fixed key, so a new version installs over the old one and keeps your settings.
Android refuses an update signed with a different key, so without it there are no updates.
The key is never stored in the repository. GitHub reads it from two repository secrets
(Settings → Secrets and variables → Actions → New repository secret):

- `ETV_KEYSTORE_BASE64`: the key file in base64 (`base64 -i etv-release.jks | pbcopy` on a Mac)
- `ETV_KEYSTORE_PASSWORD`: its password

Keep a copy of `etv-release.jks` and its password somewhere safe: losing them means uninstalling eTV once to
move to a new key. To build on your Mac with the same key, put `etv-release.jks` in `app/` (git ignores it) and
`install.sh` asks for the password. Without the key, builds fall back to a temporary one and aren't published.

## Updates

eTV looks for a new release on GitHub twice a day. When there is one, the Settings button on the home screen
says **Settings · Update**. Open it and choose **New version from GitHub → Install**: eTV downloads it, Android
asks you to confirm, and eTV comes back as the new version with all your settings. You can also check by hand
there, or turn the automatic check off.

The first time, Android asks whether eTV may install apps (on Philips: allow eTV under Unknown sources). eTV
opens that switch for you; turn it on, press Back and choose Install again.

The repository has to be public for the TV to see the releases.

## Installing on the TV

1. On the TV: turn on Developer options. Philips / Android TV: Settings → Device Preferences → About → click
   **Build** 7 times (Google TV: Settings → System → About → **Android TV OS build**). Then turn on
   **USB debugging** (and **Network debugging** if listed) in Developer options. Note the TV's IP address.
2. Easiest, from this folder on your Mac: `bash install.sh <TV-IP>`. It builds, installs and runs
   `make-default.sh`. Accept the "Allow debugging?" prompt on the TV.
3. Or by hand with the APK from GitHub: `adb connect <TV-IP>`, then `adb install -r app-release.apk`.

Moving to the fixed key: builds made before the signing key was set up (everything up to 1.3.1) used a
temporary key, so uninstall eTV once before installing the first signed release. From then on it updates itself.

## Last resort: switching off the stock launcher

Only if nothing above works. This disables the TV's own home screen over adb so Home has nowhere else to go.
Some features that live in it (recommendations, its settings panel) are gone while it's off.

```sh
adb shell pm list packages | grep -i launcher                     # find its name first
adb shell pm disable-user --user 0 com.google.android.tvlauncher   # Philips / Android TV
adb shell pm enable com.google.android.tvlauncher                  # undo
```

On Google TV the stock launcher is `com.google.android.apps.tv.launcherx`.

## Project layout

```
app/src/main/java/app/etv/launcher/
  MainActivity.kt       home screen: greeting, clock, arch row, dock, move mode, remote keys, inputs, search
  AppsActivity.kt       All apps grid with add/hide/info/uninstall
  SettingsActivity.kt   Customise
  SetupActivity.kt      Home button setup with live status
  HomeButtonService.kt  the Home button helper (accessibility service)
  HomeSetup.kt          who answers Home, and whether eTV is in charge
  Nav.kt                where you were, so Home can bring you back there
  NowPlaying.kt         the Now playing card and how it picks what to show
  Updater.kt            checks GitHub releases, downloads and installs new versions
  PrayerTimes.kt        prayers, calculation methods, the TV's own calculation, Hijri dates, the stored times
  PrayerSources.kt      aladhan.com, Mawaqit mosques, city search, keeping the times fresh
  PrayerStrip.kt        the prayer times on the home screen: a line through the day, styled for each theme
  AthanSounds.kt        the athan recordings and the dua: built in, or fetched once
  AthanService.kt       the athan alarm, and playing the athan and dua while pausing the rest of the TV
  AthanActivity.kt      the athan screen
  AthanSettings.kt      Customise → Athan
  BootReceiver.kt       opens eTV when the TV starts
  Views.kt              arch shape, arch tile, background arches, door studs, rows, focus helpers
  AppRepo.kt            lists, caches and launches apps
  Look.kt               colour palettes and fonts
  Prefs.kt              all saved options
app/src/main/assets/fonts/   El Messiri, Reem Kufi and Readex Pro (SIL Open Font License, see OFL files)
app/src/main/assets/athan/   Makkah's athan and Fajr athan (public domain, see CREDITS.txt)
app/src/main/res/raw/        Let's Encrypt's root certificates: older TVs don't know the newer ones archive.org uses
app/src/test/                HomeShots: pictures of the home screen in every theme, without a TV
scripts/check-sounds.sh      fetches every downloadable recording and says what it is
.github/workflows/shots.yml  runs both for branches named shots*, and puts the results on the branch shots-out
install.sh, make-default.sh  one-command install and Home setup from a Mac
```

minSdk 21 (Android TV 5.0+), targetSdk 35.
