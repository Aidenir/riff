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
