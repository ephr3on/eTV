# The AirPlay receiver in eTV

This folder is the AirPlay receiver eTV uses. It comes from
[flymop/airplay-tv](https://github.com/flymop/airplay-tv) (commit `a4003d8`), which is built on
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) and
[FDH2/UxPlay](https://github.com/FDH2/UxPlay). All three are under the GNU General Public License
v3.0, and so is this folder (see [LICENSE](LICENSE)). Because eTV's APK includes it, eTV as a whole
is distributed under the same licence (see the LICENSE at the top of the repository).

Native parts, as git submodules in `src/main/cpp/third_party/` at the same commits as airplay-tv:

| Submodule | What it is | Licence |
|---|---|---|
| UxPlay | the AirPlay server (RTSP, pairing, FairPlay, mirroring, HLS) | GPLv3 |
| libplist | Apple property lists | LGPLv2.1 |
| openssl-cmake | builds OpenSSL 3.4.4 (fetched from OpenSSL's GitHub release, checked by SHA-256) | MIT (OpenSSL: Apache 2.0) |
| ffmpeg | only its ALAC decoder | LGPLv2.1+ |

Oboe (Apache 2.0) and AndroidX Media3 (Apache 2.0) come from Google's Maven repository.

## What eTV changed

- **Only the engine.** airplay-tv's own screens (its home screen, settings overlay, music view) are
  gone; eTV draws its own (`app/src/main/java/app/etv/launcher/AirPlayActivity.kt`,
  `AirPlaySettings.kt`). The video player draws on a plain Surface instead of Media3's PlayerView.
- **Its own process.** The service runs in `:airplay`, so nothing in the native code can take the
  launcher down; eTV talks to it with intents (`ACTION_START_SERVER`, `ACTION_RESTART`,
  `ACTION_HOLD`, `ACTION_RELEASE`) and reads a one-line status file.
- **When the screen opens.** On the first picture, video, song or code to type, not on every
  connection (Apple devices also connect just to look). It opens `com.flymop.airplaytv.SHOW`.
- **The athan.** `ACTION_HOLD` silences the receiver, pauses a video on the TV and music on the
  phone, and keeps anything sent meanwhile quiet; `ACTION_RELEASE` brings the sound back and carries on.
- **Power and network.** The CPU is kept awake only while a device is connected; the receiver
  announces itself again when the network comes back.
- **Settings** live in their own file (`airplay`), are read afresh by the other process, and the
  default name is "eTV".
- **Build.** A library module (minSdk 26; eTV itself still starts on older TVs and just doesn't
  offer AirPlay), ARM only for the TV (`ETV_ABIS` overrides; the emulator rehearsal adds x86_64),
  and the patches in `src/main/cpp/patches/` are applied to UxPlay and openssl-cmake before each
  native build (`applyNativePatches`).

Each changed source file says so at its top.
