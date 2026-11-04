# eTV — a calm Sidi Bou Said launcher for Android TV

A home screen with arched-door app tiles, a quiet clock and greeting, and nothing that moves on its own.
No libraries, no ads, no autoplaying previews, no network access.

## Using it with the remote

| On the home screen | What it does |
|---|---|
| Left / Right | Move between apps (the selected arch fills with your highlight colour) |
| OK | Open the app |
| Hold OK | Options: Open, Move, Remove from home, App info |
| Move → Left / Right → OK | Re-order the home row |
| Down | Dock: Search, Inputs, All apps, Settings |
| Menu | Open Customise |
| Home (while already home) | Jump back to the first app |

In **All apps**, hold OK on any app to add it to home, hide it, open its info or uninstall it.

**Customise** (the Settings button) changes, instantly: highlight colour (Whitewash, Sand, Bougainvillea, Sea glass),
background (Sea blue, Night, Dusk), background arches, greeting (Arabic and English / Arabic / English / off),
12- or 24-hour clock, date, arch size, app icon or letter inside each arch, when app names show, hidden apps,
and resetting the home row.

## Getting the APK

The project can't be built without the Android SDK, so pick one:

**A. GitHub (no setup on your PC)**
1. Create a new GitHub repository and push this folder to it.
2. The included workflow (`.github/workflows/build.yml`) builds automatically. Open the **Actions** tab,
   click the finished run, and download **eTV-apk**. Unzip it to get `app-release.apk`.

**B. Android Studio**
Open this folder, wait for Gradle sync, then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**,
or run `./gradlew assembleRelease`. The APK is in `app/build/outputs/apk/release/`.

The release APK is minified and signed with the debug key, so it installs directly.

## Installing on the TV

1. On the TV: Settings → Device Preferences → About → click **Build** 7 times
   (Google TV: Settings → System → About → **Android TV OS build**), then turn on
   **USB debugging / Network debugging** in Developer options. Note the TV's IP address.
2. On your PC: `adb connect <TV-IP>` and accept the prompt on the TV.
3. `adb install -r app-release.apk`

(Alternatively, copy the APK with an app like *Send Files to TV* and open it with a file manager.)

## Making it the default home screen

**Classic Android TV:** press the remote's Home button. If the TV asks which app to use, pick **eTV → Always**.
Some TVs list it under Settings → Apps → Default apps → Home app. Customise → *Default home app* shows whether
it's set.

**Google TV (Chromecast with Google TV, newer Sony/TCL/Philips, etc.):** Google TV doesn't let you choose another
home app from Settings. The common workaround is to disable the stock launcher over adb:

```sh
adb shell pm list packages | grep -i launcher     # find the stock launcher's name
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

The Home button then opens eTV. Some Google TV features that live in the stock launcher (the For You
recommendations, profile switching, the Google TV settings panel) stop being available while it's disabled.
To undo it at any time:

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
```

Package names vary by manufacturer, so check the list from the first command before disabling anything.

## Project layout

```
app/src/main/java/app/etv/launcher/
  MainActivity.kt      home screen: greeting, clock, arch row, dock, move mode, inputs, search
  AppsActivity.kt      All apps grid with add/hide/info/uninstall
  SettingsActivity.kt  Customise
  Views.kt             arch shape, arch tile, background arches, door studs, focus helpers
  AppRepo.kt           lists, caches and launches apps
  Look.kt              colour palettes and fonts
  Prefs.kt             all saved options
app/src/main/assets/fonts/   El Messiri and Readex Pro (SIL Open Font License, see OFL files)
```

minSdk 21 (Android TV 5.0+), targetSdk 35.
