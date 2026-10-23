//! What differs between the clients built on this core: logging, default
//! folders and how the core reaches the Java side on Android.

#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "macos")]
mod macos;

#[cfg(target_os = "android")]
pub use android::*;
#[cfg(target_os = "macos")]
pub use macos::*;
