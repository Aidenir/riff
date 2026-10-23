# Vendored crates

`librespot-core` is librespot-core 0.8.0 from crates.io with one change in
`src/config.rs`: on Android, `OS` is `"linux"`, so the Android client presents
itself to Spotify the same way the Linux app does. Riff's login goes through
the desktop OAuth client, and Spotify refuses the per-track tokens when the
client token and client ID claim to be Android.

It is used through `[patch.crates-io]` in the workspace `Cargo.toml`. On
Linux and macOS the code is unchanged.

The vendored copy also turns one `#[expect(deprecated)]` in
`src/authentication.rs` into `#[allow(deprecated)]`: as a path dependency its
lints are no longer capped, and the expectation goes unmet on Linux.

`src/audio_key.rs` adds a circuit breaker: after Spotify refuses an audio
key, further key requests fail at once without contacting Spotify for
10 seconds, doubling with each refusal in a row up to 5 minutes, and reset by
the first key that is granted. Without it, a refusal is followed by a retry
for the same track (after librespot drops the cached file), a request for
the next track and the preload of the one after, which keeps the account
rate limited. See librespot issue #1649.
