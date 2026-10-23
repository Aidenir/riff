use std::path::PathBuf;

use crate::CoreConfig;

const APP_ID: &str = "com.aidenir.Riff";

pub fn init_logging() {
    let _ = env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("riff=info,riff_ffi=info,librespot=warn"),
    )
    .try_init();
}

fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_default()
}

/// `~/Library/Application Support/com.aidenir.Riff` and `~/Library/Caches`,
/// unless `RIFF_SUPPORT_DIR` or `RIFF_CACHE_DIR` say otherwise.
pub fn default_config() -> CoreConfig {
    let support_dir = std::env::var_os("RIFF_SUPPORT_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            home()
                .join("Library")
                .join("Application Support")
                .join(APP_ID)
        });
    let cache_dir = std::env::var_os("RIFF_CACHE_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join("Library").join("Caches"));
    CoreConfig {
        support_dir: support_dir.to_string_lossy().into_owned(),
        cache_dir: cache_dir.to_string_lossy().into_owned(),
    }
}
