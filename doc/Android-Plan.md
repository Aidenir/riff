# Native Android Client Plan

Plan for a native Android version of Riff with feature parity to the macOS app, built on the same Rust core. It follows [the macOS plan](macOS-Plan.md) and reuses what that port taught us.

## Decisions

| Topic | Decision |
| --- | --- |
| UI stack | Kotlin and Jetpack Compose (Material 3 components), on top of the Rust core through UniFFI's Kotlin bindings. |
| Rust core | One shared `crates/riff-ffi` for macOS and Android. Platform differences live in small per-platform modules. |
| Form factor | Phones only. Portrait first, landscape supported without a separate layout. |
| Minimum OS | Android 13 (API 33). Target the current API level. |
| Scope | Full parity with the macOS app, built in vertical slices. |
| Android extras | Open `open.spotify.com` links and accept shared links ("Share to Riff"). Media notification, lock screen, Bluetooth and headset controls, and background playback are always included. No widget, Android Auto or TV. |
| Navigation | Bottom bar with Home, Search and Library. Library has chips for Albums, Playlists (with folders), Liked Songs and Artists. A mini player sits above the bar; tapping or swiping it up opens Now Playing, which has Queue and Lyrics tabs. |
| Design | Fixed Riff theme from the app icon colours (green and teal primary, blue secondary, neutral surfaces), with the light, dark and system setting the other apps have. No dynamic colour. Art tint on Now Playing and detail headers, as on the Mac. |
| App id | `com.aidenir.riff`, app name "Riff". |
| Distribution | Release-signed APK attached to GitHub releases. No Google Play, no F-Droid. |
| Signing | A release keystore generated once, stored outside the repo, backed up by Viktor (for example in 1Password), and provided to CI as GitHub Actions secrets. |
| Toolchain | rustup replaces Homebrew's Rust. Android targets `aarch64-linux-android` and `x86_64-linux-android`, `cargo-ndk`, and the NDK and SDK platform installed with the existing `sdkmanager`. No Android Studio. |
| ABIs | `arm64-v8a` for phones, `x86_64` for the emulator. |
| Testing | Viktor's phone over adb (USB or wifi), with a debug-only smoke test like the Mac's. Unit tests in Rust and Kotlin. |
| Translations | `po/*.po` converted to `values-<lang>/strings.xml` by a script, skipping `en.po`. |
| CI | New workflow `riff-android.yml`: Rust tests, Kotlin unit tests, a release APK, upload, and attach to releases on `v*` tags. |

## Changes from the plan

Found while building, decided without stopping:

- **librespot identifies as Linux on Android.** librespot picks Spotify's Android client ID and an Android client token from a compile-time `OS` constant. Riff logs in through the desktop OAuth client, so every track load failed (`INVALID_CREDENTIALS`, then `BAD_REQUEST` from login5) and Riff reported it as a DRM block. `vendor/librespot-core` is librespot-core 0.8.0 with `OS` set to `"linux"` on Android, used through `[patch.crates-io]`. Linux and macOS are unchanged.
- **TLS by target, not by feature.** Following lesson 3, `reqwest` and librespot use rustls with webpki roots through `cfg(target_os = "android")` dependency tables instead of a `rustls` feature. `scripts/generate-spotify-api.sh` patches the generated crate the same way. No OpenSSL ends up in the Android build.
- **`uniffi-bindgen` is its own crate** (`crates/uniffi-bindgen`). Bindings are generated on the build host, which is Linux in CI, where `riff-ffi` compiles to nothing.
- **`CoreError::Failed` carries `reason`, not `message`.** Kotlin exceptions already have a `message` property and the generated code did not compile.
- **The script drives the build, not Gradle.** `scripts/build-android.sh` runs cargo-ndk, the bindings and the translations, then Gradle. The Rust core is always built in release mode; unoptimised librespot decodes too slowly.
- **compileSdk and targetSdk 37.** Compose BOM 2026.09 requires compiling against 37.
- **Own back stacks instead of navigation-compose.** A small `Navigator` keeps one stack per tab, with a page model per entry, so switching tabs keeps loaded pages and scroll positions.
- **Debug builds are `com.aidenir.riff.debug`**, so a debug and a release build can be installed side by side. The smoke test command is `adb shell am start -n com.aidenir.riff.debug/com.aidenir.riff.MainActivity -e smoke 1`, wrapped by `./scripts/test-android.sh --smoke`.
- **A floating dock instead of a plain navigation bar.** Asked for during review: the mini player and the tabs share one rounded, art-tinted card, and the selected tab grows into a gradient pill. Startup shows an animated intro after the system splash, and loading uses bouncing equalizer bars and shimmering artwork placeholders.
- **The release key lives in `~/.riff/`** (`riff-release.jks` and `release-signing.env`) on Viktor's Mac. It still needs a backup and the four GitHub secrets (`RIFF_KEYSTORE_BASE64`, `RIFF_KEYSTORE_PASSWORD`, `RIFF_KEY_ALIAS`, `RIFF_KEY_PASSWORD`).

## What the macOS port taught us

Each of these shapes a decision above or a step below.

1. **App state is per client.** The GTK state holds GObject models and cannot be shared. The Rust playback controller in `riff-ffi` (queue, skip-explicit, paged contexts, Connect routing) can, and it already has tests. Android reuses it unchanged. Page state lives in Kotlin view models.
2. **Feature flags beat forks.** The shared crates build without GTK through default features (`gtk`, `gsettings`, `secret-service`). Android uses the same no-default path, so no shared crate changes are needed for GTK.
3. **Gate the core by target, not by feature.** `riff-ffi` is `#![cfg(target_os = "macos")]` with target-only dependencies, so Linux builds never touch it. The gate becomes `any(target_os = "macos", target_os = "android")`, and the Linux build stays untouched.
4. **Paths and secrets must come from the app.** The core finds its folders from `HOME` (`~/Library/...`), and credentials use the Keychain only on macOS (other targets fall back to memory). Android has no `HOME` and no Keychain, so the app passes its directories and a secret store into the core at startup.
5. **TLS needs a choice.** Everything uses `native-tls`, which on Android means cross-compiling OpenSSL. librespot 0.8 and reqwest both offer rustls, so Android builds use rustls with bundled webpki roots and avoid OpenSSL entirely.
6. **Generate bindings with `--metadata-no-deps`.** Without it, `cargo metadata` downloads every platform's crates and hangs.
7. **`po/en.po` renames GTK labels** ("Library" becomes "Albums"). Native clients must skip it.
8. **The core owns settings it changes itself.** Volume, shuffle, repeat and the DRM marker are kept from the store when the UI saves settings, or a stale UI copy rolls them back. Android uses the same `update_settings`.
9. **Do not reach shared UI state through ambient lookups in system chrome.** The Mac crash came from a toolbar item reading the model from the SwiftUI environment. On Android, top bars, the notification and other system surfaces get their state passed in, not read from a `CompositionLocal` or a static.
10. **Sign properly from the first build.** Ad-hoc signatures changed per build and made macOS ask for Keychain access every time. Android uses one release key from slice 1; debug builds use one checked debug key, so Keystore data survives reinstalls.
11. **A smoke test finds what compiling does not.** The debug smoke test (29 steps through every page and playback action) caught the real crash and four layout bugs. Android gets the same driver from slice 2, run with `adb shell am start -e smoke 1`, with logs from logcat and screenshots from `adb exec-out screencap`.
12. **Logging in needs the person.** Everything after login waits for Viktor to log in once on the phone. Plan that step early, and keep credentials across reinstalls.
13. **Keep the Linux checks.** A Fedora container checks the Linux build, and new dependencies in `Cargo.lock` mean regenerating `flatpak/cargo-sources.json`. The Android crates (rustls, Android audio) add entries too.
14. **Watch disk space.** Two ABIs in release plus Gradle caches take tens of gigabytes. Clean `target/` per ABI after CI-style builds.

## Architecture

```
  +-------------------+   +--------------------+   +-----------------------------+
  |  GTK app (src/)   |   |  macOS (macos/)    |   |  Android (android/)         |
  |  widgets, state   |   |  SwiftUI, AppModel |   |  Compose, view models,      |
  |                   |   |                    |   |  Media3 session, service    |
  +---------+---------+   +---------+----------+   +--------------+--------------+
            |                       |                             |
            |                       +-------------+---------------+
            |                                     |
            |                     +---------------+----------------+
            |                     |  riff-ffi (UniFFI)             |
            |                     |  RiffCore, playback controller,|
            |                     |  Connect, settings, events     |
            |                     |  platform: macos.rs/android.rs |
            |                     +---------------+----------------+
            |                                     |
  +---------+-------------------------------------+-----------------+
  |  riff-player: librespot session and player, PlayerDelegate     |
  +---------+--------------------------------------------------------+
            |
  +---------+-+-----------+-----------+-------------+-------------------+
  | riff-core | riff-api  | riff-auth | riff-config | riff-audio-engine |
  +-----------+-----------+-----------+-------------+-------------------+
```

### Rust work

1. **Widen the `riff-ffi` gate** to macOS and Android. Move the platform pieces into `platform/macos.rs` and `platform/android.rs`.
2. **Startup configuration.** `RiffCore::new` takes a `CoreConfig` record with the support, cache and librespot directories. macOS fills it from the paths it uses today; Android passes `filesDir` and `cacheDir`. The api disk cache and settings store read from it instead of `HOME`.
3. **Secret store as a foreign trait.** `riff-auth` gains a `SecretStore` trait (get, set, delete by account). macOS keeps the Keychain implementation. Android implements it in Kotlin: AES-GCM keys in the Android Keystore, encrypted blobs in app storage. The core receives it through UniFFI `with_foreign`.
4. **TLS feature.** A `rustls` feature on `riff-api`, `riff-auth` and `riff-player` switches reqwest and librespot to rustls with webpki roots. Android enables it; Linux and macOS keep `native-tls`.
5. **Audio output.** librespot's `rodio` backend uses cpal, which plays through AAudio on Android. `AudioBackend::System` works unchanged. Audio focus and "becoming noisy" (headphones unplugged) are handled in Kotlin and turned into pause commands.
6. **Login.** The player's OAuth flow serves the redirect on `127.0.0.1:8898`. On Android the app opens the authorisation URL in a Custom Tab; the redirect reaches the in-process loopback server as on desktop. Fallback if Chrome blocks loopback: add `com.aidenir.riff://login` as a second redirect URI and pass the code to the core.
7. **Image sizes.** `image()` already returns bytes; Android decodes them with `ImageDecoder` into a `LruCache` sized from memory class.

### Android app (`android/`)

```
android/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
  app/
    build.gradle.kts            cargo-ndk task builds riff-ffi for both ABIs before the Kotlin build
    src/main/
      AndroidManifest.xml       app, playback service, link and share intent filters
      java/com/aidenir/riff/
        RiffApplication.kt      creates RiffCore with paths and the Keystore secret store
        core/                   event bridge (Listener to StateFlow), repositories over RiffCore
        playback/               MediaSessionService, Media3 player facade, audio focus, notification
        ui/                     theme, navigation, mini player, now playing, pages, components
        ui/pages/               home, search, library, album, artist, playlist, profile, queue, lyrics, settings
        debug/SmokeTest.kt      debug-only driver
      res/                      icon (adaptive, from the SVG), strings, values-<lang> (generated)
    src/test/                   view model and mapping tests
  uniffi/                       generated Kotlin bindings (gitignored)
```

- **State.** An `AppState` singleton turns core events into `StateFlow`s (session, playback, queue, settings, library version). Screens use view models that call the suspend functions UniFFI generates for the core's async methods.
- **Playback service.** A Media3 `MediaSessionService` holds a `SimpleBasePlayer` facade. It mirrors `PlaybackSnapshot` into the session (metadata, artwork, position, shuffle and repeat), and forwards commands from the notification, lock screen and Bluetooth to the core. librespot keeps producing the audio itself. The service runs in the foreground only while playing or briefly after pausing, so Android does not kill the session mid-song.
- **Links.** Intent filters for `https://open.spotify.com/{track,album,artist,playlist,user}/...` (not verified app links, so Android shows a chooser when the Spotify app is installed) and `text/plain` shares. Both go through `parse_spotify_link`.

### Mac features mapped to Android

| macOS | Android |
| --- | --- |
| Sidebar sections | Bottom bar plus Library chips |
| Playlist folders in the sidebar | Folder rows in the Library playlists list, opening a nested list |
| Pinned sidebar items | Pinned row at the top of Library (feature flag) |
| Toolbar back and forward | System back and predictive back gesture |
| Track table and context menu | Track rows with a bottom-sheet action menu; long press for multi-select |
| Queue inspector | Queue tab in Now Playing, drag to reorder, swipe to remove |
| Menu bar shortcuts | Not needed (hardware keyboard shortcuts kept for play, pause and seek) |
| Settings window | Settings screen with the same sections |
| Debug menu | Debug settings section in debug builds |
| Now Playing (`MPNowPlayingInfoCenter`) | Media3 session and media notification |
| Sleep prevention (IOPM) | Foreground service with a partial wake lock while playing |
| Clipboard link banner | Share sheet target and link intent filter |
| Keychain | Android Keystore through the `SecretStore` trait |

### Build

- `./scripts/build-android.sh [debug|release] [--install]`:
  1. Generate the Spotify API crate if missing.
  2. `cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p riff-ffi --features android`.
  3. Generate Kotlin bindings with `uniffi-bindgen --metadata-no-deps --language kotlin`.
  4. Convert `po/` to `values-<lang>/strings.xml`.
  5. `./gradlew assembleDebug` or `assembleRelease`; `--install` runs `adb install -r`.
- `./scripts/test-android.sh`: Rust tests, Kotlin unit tests, and the smoke test on a connected device.
- Release signing reads `RIFF_KEYSTORE`, `RIFF_KEYSTORE_PASSWORD`, `RIFF_KEY_ALIAS` and `RIFF_KEY_PASSWORD` from the environment or CI secrets.

### CI

`riff-android.yml` on `ubuntu-latest`: set up Java 17, the Android SDK and NDK, rustup with the Android targets and cargo-ndk; run the Rust and Kotlin tests; build a release APK; upload it; attach it to the release on `v*` tags. The Linux and macOS workflows stay as they are.

## Slices

Each slice ends with the Linux and macOS builds still green, the Android app building, and its checklist rows ticked.

1. **Toolchain and core.** Install rustup, targets, cargo-ndk, NDK and SDK. Widen the `riff-ffi` gate, add `CoreConfig`, the `SecretStore` trait and the rustls feature. Cross-compile for both ABIs. macOS app still builds and its smoke test passes.
2. **App shell, login, playback.** Gradle project, theme, bottom bar, Custom Tab login, mini player, Now Playing, the Media3 service and notification, audio focus. Release keystore created and secrets added. Viktor logs in once on the phone. Smoke test driver added.
3. **Library.** Library tab with albums, playlists in folders, liked songs and artists, artwork cache, track rows with the action sheet, like and follow.
4. **Search and details.** Search with scopes, album, artist, playlist and profile pages, playlist create and edit with cover picker, add to and remove from playlists, recommendations, link intents and share target.
5. **Queue, lyrics, home.** Queue tab with reordering, synced lyrics tab, home shelves.
6. **Settings, audio engine, Connect.** Settings screen with every option, equalizer, pan, pitch, normalisation, caches, Connect device picker, pinning, debug section.
7. **Release.** Adaptive icon, translations, release APK, CI workflow, README and screenshots, Flatpak cargo sources refreshed.

## Parity checklist

Same rows as the macOS checklist, for Android.

### Shell

- [x] Bottom bar navigation with Home, Search and Library
- [x] System back and predictive back
- [x] Mini player and swipe-up Now Playing
- [x] Queue tab in Now Playing
- [x] Snackbars for notifications
- [x] Spotify links: intent filter and share target
- [x] Device selector (Spotify Connect), behind `DeviceSelector`
- [x] Pinned items, behind `PinnedObjects`
- [x] Art tint
- [x] About screen

### Pages

- [x] Login
- [x] Home
- [x] Saved albums
- [x] Saved playlists, including folders
- [x] Liked songs
- [x] Followed artists
- [x] Search (all scopes)
- [x] Album
- [x] Artist
- [x] Playlist, including recommendations
- [x] Profile
- [x] Queue
- [x] Now Playing
- [x] Lyrics (synced)
- [x] Settings

### Playback and system

- [x] Play, pause, next, previous, seek, volume
- [x] Shuffle with separation, repeat modes
- [x] Gapless playback
- [x] Skip explicit tracks
- [x] Background playback with a foreground service
- [x] Media notification, lock screen, Bluetooth and headset buttons (media buttons and notification verified; lock screen controls are off in the test phone's settings)
- [x] Audio focus (pause for calls, duck for navigation) and pause when headphones unplug (focus verified; calls and unplugging not tried)
- [x] Credentials in the Android Keystore, kept across app updates

### Settings

- [x] Theme (light, dark, system)
- [x] Skip explicit tracks, shuffle separation
- [x] Audio quality, audio format, gapless, volume curve, mono
- [x] Normalisation (all options, behind the flag)
- [x] Equalizer, stereo pan, pitch shift
- [x] Access point port
- [x] Memory, disk and audio cache sizes, clear cache

Not carried over from the desktop apps: close-window behaviour, keep-awake setting (Android handles it with the playback service), queue side panel, keyboard shortcuts window.

## Risks

- **cpal and AAudio under librespot.** Not exercised in this codebase. Slice 1 cross-compiles; slice 2 proves audio on the phone before UI work grows. Fallback: a small Rust sink that hands PCM to an `AudioTrack` through a foreign trait.
- **Background limits.** Some phone makers kill background apps aggressively. The foreground service covers stock Android; vendor-specific battery settings may still need a note in the README.
- **Loopback OAuth in a Custom Tab.** If the browser refuses the `127.0.0.1` redirect, use the app-scheme redirect described above.
- **Link handling.** Without verified app links, the Spotify app (if installed) competes for `open.spotify.com` links, so Android shows a chooser.
- **Spotify terms and PlayPlay DRM.** Same as on Linux and macOS. Sideloaded APK only.
- **Signing key loss.** Losing the keystore means users must uninstall to update. Back it up the day it is created.
