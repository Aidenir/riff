//! Credential storage supplied by the client app.
//!
//! Platforms without a store the core can reach by itself (Android) hand one
//! in at startup. Without one, credentials only live for the session.

use std::sync::{Arc, OnceLock};

/// Keeps one secret per account name.
pub trait SecretStore: Send + Sync {
    fn get(&self, account: &str) -> Option<Vec<u8>>;
    fn set(&self, account: &str, secret: &[u8]) -> bool;
    fn delete(&self, account: &str);
}

static STORE: OnceLock<Arc<dyn SecretStore>> = OnceLock::new();

/// Register the app's secret store. Only the first call has an effect.
pub fn set_secret_store(store: Arc<dyn SecretStore>) {
    let _ = STORE.set(store);
}

#[cfg_attr(any(target_os = "macos", feature = "secret-service"), allow(dead_code))]
pub(crate) fn secret_store() -> Option<&'static Arc<dyn SecretStore>> {
    STORE.get()
}
