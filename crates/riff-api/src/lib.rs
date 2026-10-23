#[macro_use]
extern crate log;

pub mod models;

mod defaults;
mod error;
mod i18n;
mod token;

pub(crate) mod cache;
pub(crate) mod http;
mod image;
mod lrclib;
pub(crate) mod providers;
mod scheduler;
pub(crate) mod service;

#[cfg(debug_assertions)]
pub(crate) mod dev;

#[cfg(not(feature = "gtk"))]
pub use cache::disk::set_cache_root;
pub use cache::LoadedImage;
pub use error::DomainError;
pub use providers::spotify::domain::{check_user_profile, UserProfileCheck};
pub use providers::spotify::spotify_service;
pub use scheduler::{Load, LoadPriority, BACKGROUND_EPOCH};
pub use service::ApiService;

#[cfg(debug_assertions)]
pub use dev::{is_simulate_offline, set_injected_error, set_simulate_offline};

pub use models::{Device, DeviceKind, PlayerState, Queue, RepeatMode};
pub use token::TokenProvider;
