# eTV — a calm Sidi Bou Said launcher for Android TV

A home screen with arched-door app tiles, a quiet clock and greeting, and nothing that moves on its own.
No libraries, no ads, no autoplaying previews, no network access.

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

Every push to GitHub builds it: open the repository's **Actions** tab, click the latest run and download
**eTV-apk** under Artifacts. Unzip it to get `app-release.apk`.

Or open this folder in Android Studio and run `./gradlew assembleRelease`
(the APK is in `app/build/outputs/apk/release/`).

Builds are signed with one fixed key, so a new version installs over the old one and keeps your settings.
The key is never stored in the repository. GitHub reads it from two repository secrets
(Settings → Secrets and variables → Actions):

- `ETV_KEYSTORE_BASE64`: the key file in base64 (`base64 -i etv-release.jks | pbcopy` on a Mac)
- `ETV_KEYSTORE_PASSWORD`: its password

To build on your Mac with the same key, put `etv-release.jks` in `app/` (git ignores it) and `install.sh` asks for
the password. Without the key, builds fall back to a temporary one.

## Installing on the TV

1. On the TV: turn on Developer options. Philips / Android TV: Settings → Device Preferences → About → click
   **Build** 7 times (Google TV: Settings → System → About → **Android TV OS build**). Then turn on
   **USB debugging** (and **Network debugging** if listed) in Developer options. Note the TV's IP address.
2. Easiest, from this folder on your Mac: `bash install.sh <TV-IP>`. It builds, installs and runs
   `make-default.sh`. Accept the "Allow debugging?" prompt on the TV.
3. Or by hand with the APK from GitHub: `adb connect <TV-IP>`, then `adb install -r app-release.apk`.

Updating from version 1.0: that build was signed with a temporary key, so uninstall it once first
(`adb uninstall app.etv.launcher`, or uninstall eTV on the TV). `install.sh` does this for you.

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
  BootReceiver.kt       opens eTV when the TV starts
  Views.kt              arch shape, arch tile, background arches, door studs, rows, focus helpers
  AppRepo.kt            lists, caches and launches apps
  Look.kt               colour palettes and fonts
  Prefs.kt              all saved options
app/src/main/assets/fonts/   El Messiri and Readex Pro (SIL Open Font License, see OFL files)
install.sh, make-default.sh  one-command install and Home setup from a Mac
```

minSdk 21 (Android TV 5.0+), targetSdk 35.
