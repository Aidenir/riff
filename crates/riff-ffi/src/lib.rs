//! Core of the native macOS and Android clients.
//!
//! [`RiffCore`] owns what `App::new` wires up in the GTK app: the token
//! stores, the api service, the librespot player and a playback controller.
//! Swift and Kotlin talk to it through UniFFI: async methods for data, plain
//! methods for playback commands, and a [`Listener`] for events coming back.

#![cfg(any(target_os = "macos", target_os = "android"))]

#[macro_use]
extern crate log;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use futures::channel::mpsc::UnboundedSender;
use riff_api::models::{SearchType, Track};
use riff_api::{ApiService, Load, LoadPriority};
use riff_auth::{SessionTokens, TokenStore};
use riff_core::play_queue::{EntryKey, SongsSource};
use riff_player::{Command, PlayerDelegate, SpotifyError, SpotifyPlayerSettings};
use url::Url;

mod connect;
mod models;
mod platform;
mod playback;
mod settings;

pub use models::*;
pub use settings::*;

use playback::{Msg, PlaybackHandle, PlaybackInit};

uniffi::setup_scaffolding!();

// Track registry cap; the registry is cleared when it grows past this.
const MAX_REGISTERED_TRACKS: usize = 20_000;

static EPOCH: AtomicU64 = AtomicU64::new(1);

pub(crate) fn current_epoch() -> u64 {
    EPOCH.load(Ordering::Relaxed)
}

/// Receives everything the core reports. Called from background threads, so
/// implementations hop to the main actor themselves.
#[uniffi::export(with_foreign)]
pub trait Listener: Send + Sync {
    fn on_event(&self, event: CoreEvent);
}

/// Folders the core keeps its files in, chosen by the app.
#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct CoreConfig {
    /// Settings live here.
    pub support_dir: String,
    /// Api, image and audio caches live under `<cache_dir>/riff`.
    pub cache_dir: String,
}

/// The folders the macOS client has always used.
#[uniffi::export]
pub fn default_core_config() -> CoreConfig {
    platform::default_config()
}

/// Credential storage implemented by the app, for platforms where the core
/// cannot reach a keychain itself (the Android Keystore).
#[uniffi::export(with_foreign)]
pub trait SecretStore: Send + Sync {
    fn get(&self, account: String) -> Option<Vec<u8>>;
    fn set(&self, account: String, secret: Vec<u8>) -> bool;
    fn delete(&self, account: String);
}

struct ForeignSecrets(Arc<dyn SecretStore>);

impl riff_auth::SecretStore for ForeignSecrets {
    fn get(&self, account: &str) -> Option<Vec<u8>> {
        self.0.get(account.to_string())
    }

    fn set(&self, account: &str, secret: &[u8]) -> bool {
        self.0.set(account.to_string(), secret.to_vec())
    }

    fn delete(&self, account: &str) {
        self.0.delete(account.to_string())
    }
}

/// How eagerly a request should be served, mapped onto the api scheduler.
#[derive(uniffi::Enum, Clone, Copy, Debug)]
pub enum Priority {
    Background,
    Offscreen,
    Visible,
    Hero,
}

fn load(priority: Priority) -> Load {
    let priority = match priority {
        Priority::Background => LoadPriority::Background,
        Priority::Offscreen => LoadPriority::Offscreen,
        Priority::Visible => LoadPriority::Visible,
        Priority::Hero => LoadPriority::Hero,
    };
    Load::new(priority, current_epoch())
}

fn visible() -> Load {
    load(Priority::Visible)
}

/// Forwards librespot feedback into the playback controller and the app.
struct CorePlayerDelegate {
    listener: Arc<dyn Listener>,
    playback: Mutex<Option<PlaybackHandle>>,
    settings: Arc<SettingsStore>,
    user: Arc<Mutex<Option<String>>>,
    librespot_cache: PathBuf,
}

impl CorePlayerDelegate {
    fn send(&self, msg: Msg) {
        if let Some(playback) = self.playback.lock().unwrap().as_ref() {
            playback.send(msg);
        }
    }
}

impl PlayerDelegate for CorePlayerDelegate {
    fn end_of_track_reached(&self) {
        self.send(Msg::TrackEnded);
    }

    fn skip_track(&self) {
        self.send(Msg::SkipTrack);
    }

    fn stop_playback(&self) {
        self.send(Msg::StopPlayback);
    }

    fn token_login_successful(&self, username: String) {
        *self.user.lock().unwrap() = Some(username.clone());
        self.listener.on_event(CoreEvent::LoggedIn { username });
    }

    fn refresh_successful(&self) {
        debug!("token refreshed");
    }

    fn set_explicit_filter_locked(&self, locked: bool) {
        self.send(Msg::ExplicitFilterLocked(locked));
    }

    fn set_skip_explicit(&self, skip: bool) {
        self.settings.update(|s| s.skip_explicit = skip);
        self.send(Msg::SetSkipExplicit(skip));
    }

    fn report_error(&self, error: SpotifyError) {
        let event = match error {
            SpotifyError::NotPremium => CoreEvent::NotPremium,
            SpotifyError::LoginFailed => CoreEvent::LoginFailed,
            SpotifyError::LoggedOut => {
                *self.user.lock().unwrap() = None;
                CoreEvent::LoggedOut
            }
            SpotifyError::PlaybackDrmBlocked => CoreEvent::DrmBlocked,
            SpotifyError::PlaybackTemporarilyUnavailable => CoreEvent::Notification {
                message: "Playback is Temporarily Unavailable".into(),
            },
            error => CoreEvent::Notification {
                message: error.to_string(),
            },
        };
        self.listener.on_event(event);
    }

    fn notify_playback_state(&self, position: u32) {
        self.send(Msg::SyncPosition(position));
    }

    fn preload_next_track(&self) {
        self.send(Msg::PreloadNext);
    }

    fn login_challenge_started(&self, url: Url) {
        self.listener.on_event(CoreEvent::OpenLoginUrl {
            url: url.to_string(),
        });
    }

    fn set_connection_lost(&self, lost: bool) {
        self.listener.on_event(CoreEvent::ConnectionLost { lost });
    }

    fn load_settings(&self) -> Option<SpotifyPlayerSettings> {
        Some(self.settings.get().player_settings())
    }

    fn drm_verified_user(&self) -> String {
        self.settings.get().drm_verified_user
    }

    fn set_drm_verified_user(&self, user_id: &str) {
        self.settings
            .update(|s| s.drm_verified_user = user_id.to_string());
    }

    fn clear_drm_verified_user(&self) {
        self.settings.update(|s| s.drm_verified_user.clear());
    }

    fn librespot_cache_root(&self) -> PathBuf {
        self.librespot_cache.clone()
    }

    fn audio_cache_size_limit_bytes(&self) -> u64 {
        self.settings.get().audio_cache_size_mb as u64 * 1024 * 1024
    }
}

#[derive(uniffi::Object)]
pub struct RiffCore {
    runtime: tokio::runtime::Runtime,
    api: Arc<ApiService>,
    player: UnboundedSender<Command>,
    playback: PlaybackHandle,
    settings: Arc<SettingsStore>,
    listener: Arc<dyn Listener>,
    tracks: Mutex<HashMap<String, Track>>,
    user: Arc<Mutex<Option<String>>>,
    librespot_cache: PathBuf,
}

impl RiffCore {
    fn register<'a>(&self, tracks: impl IntoIterator<Item = &'a Track>) {
        let mut registry = self.tracks.lock().unwrap();
        if registry.len() > MAX_REGISTERED_TRACKS {
            registry.clear();
        }
        for track in tracks {
            registry.insert(track.rri.id.clone(), track.clone());
        }
    }

    fn infos(&self, tracks: &[Track]) -> Vec<TrackInfo> {
        self.register(tracks);
        tracks.iter().map(TrackInfo::from).collect()
    }

    fn lookup(&self, ids: &[String]) -> Vec<Track> {
        let registry = self.tracks.lock().unwrap();
        ids.iter()
            .filter_map(|id| registry.get(id).cloned())
            .collect()
    }

    /// Run `future` on the core's runtime and wait for it from the caller.
    async fn spawn<T, F>(&self, future: F) -> Result<T, CoreError>
    where
        T: Send + 'static,
        F: std::future::Future<Output = Result<T, CoreError>> + Send + 'static,
    {
        self.runtime
            .spawn(future)
            .await
            .map_err(|e| CoreError::Failed {
                reason: e.to_string(),
            })?
    }

    fn user_id(&self) -> Result<String, CoreError> {
        self.user
            .lock()
            .unwrap()
            .clone()
            .ok_or(CoreError::NotLoggedIn)
    }

    fn apply_settings(&self, old: &AppSettings, new: &AppSettings) {
        if settings::requires_player_reload(old, new) {
            let _ = self.player.unbounded_send(Command::ReloadSettings);
        }
        if old.eq_bands != new.eq_bands {
            let _ = self.player.unbounded_send(Command::SetEqualizer {
                bands: new.eq_array(),
            });
        }
        if old.mono_audio != new.mono_audio {
            let _ = self.player.unbounded_send(Command::SetMono {
                enabled: new.mono_audio,
            });
        }
        if old.pan != new.pan {
            let _ = self.player.unbounded_send(Command::SetPan { pan: new.pan });
        }
        if old.pitch_cents != new.pitch_cents {
            let _ = self.player.unbounded_send(Command::SetPitch {
                cents: new.pitch_cents,
            });
        }
        if old.skip_explicit != new.skip_explicit {
            self.playback.send(Msg::SetSkipExplicit(new.skip_explicit));
        }
        if old.shuffle_separation != new.shuffle_separation {
            self.playback
                .send(Msg::SetSeparation(new.shuffle_separation));
        }
        if old.memory_cache_size_mb != new.memory_cache_size_mb {
            self.api
                .set_memory_cache_bytes(new.memory_cache_size_mb.max(1) as usize * 1024 * 1024);
        }
        if old.disk_cache_size_mb != new.disk_cache_size_mb {
            let api = Arc::clone(&self.api);
            let bytes = new.disk_cache_size_mb.max(1) as usize * 1024 * 1024;
            self.runtime
                .spawn(async move { api.set_disk_cache_bytes(bytes).await });
        }
    }
}

fn parse_key(key: &str) -> Option<EntryKey> {
    key.parse().ok()
}

#[uniffi::export(async_runtime = "tokio")]
impl RiffCore {
    /// `secrets` stores credentials where the core has no keychain of its
    /// own (Android); macOS passes `None` and uses the Keychain.
    #[uniffi::constructor]
    pub fn new(
        listener: Arc<dyn Listener>,
        config: CoreConfig,
        secrets: Option<Arc<dyn SecretStore>>,
    ) -> Arc<Self> {
        platform::init_logging();
        let cache_dir = PathBuf::from(&config.cache_dir);
        riff_api::set_cache_root(cache_dir.clone());
        if let Some(secrets) = secrets {
            riff_auth::set_secret_store(Arc::new(ForeignSecrets(secrets)));
        }
        let librespot_cache = cache_dir.join("riff").join("librespot");

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("riff-core")
            .build()
            .expect("tokio runtime");
        let settings = Arc::new(SettingsStore::open(&PathBuf::from(&config.support_dir)));
        let current = settings.get();

        let token_store = TokenStore::new();
        let session_tokens = SessionTokens::new();
        let api = {
            let _guard = runtime.enter();
            Arc::new(riff_api::spotify_service(
                Arc::new(token_store.clone()),
                Arc::new(session_tokens.clone()),
                current.memory_cache_size_mb.max(1) as usize * 1024 * 1024,
                current.disk_cache_size_mb.max(1) as usize * 1024 * 1024,
            ))
        };

        let user = Arc::new(Mutex::new(None));
        let delegate = Arc::new(CorePlayerDelegate {
            listener: Arc::clone(&listener),
            playback: Mutex::new(None),
            settings: Arc::clone(&settings),
            user: Arc::clone(&user),
            librespot_cache: librespot_cache.clone(),
        });
        let player = riff_player::start_player_service(
            current.player_settings(),
            delegate.clone(),
            token_store,
            session_tokens,
        );
        let playback = playback::start(
            runtime.handle(),
            Arc::clone(&api),
            player.clone(),
            Arc::clone(&listener),
            PlaybackInit {
                volume: current.volume,
                shuffle: current.shuffle,
                repeat: current.repeat_mode(),
                skip_explicit: current.skip_explicit,
                separation: current.shuffle_separation,
            },
        );
        *delegate.playback.lock().unwrap() = Some(playback.clone());
        let _ = player.unbounded_send(Command::PlayerSetVolume(current.volume));

        Arc::new(Self {
            runtime,
            api,
            player,
            playback,
            settings,
            listener,
            tracks: Mutex::new(HashMap::new()),
            user,
            librespot_cache,
        })
    }

    // Session

    /// Log in with stored credentials, or report `LoggedOut`.
    pub fn restore_session(&self) {
        let _ = self.player.unbounded_send(Command::Restore);
    }

    /// Start the browser OAuth flow; `OpenLoginUrl` follows.
    pub fn start_login(&self) {
        let _ = self.player.unbounded_send(Command::InitLogin);
    }

    pub fn logout(&self) {
        self.playback.send(Msg::Stop);
        *self.user.lock().unwrap() = None;
        let _ = self.player.unbounded_send(Command::Logout);
        self.listener.on_event(CoreEvent::LoggedOut);
    }

    pub fn username(&self) -> Option<String> {
        self.user.lock().unwrap().clone()
    }

    /// Mark a navigation so requests from the previous view lose priority.
    pub fn bump_epoch(&self) -> u64 {
        EPOCH.fetch_add(1, Ordering::Relaxed) + 1
    }

    // Settings

    pub fn settings(&self) -> AppSettings {
        self.settings.get()
    }

    /// Save settings edited in the UI. Values the core keeps up to date
    /// itself (volume, shuffle, repeat, DRM marker) are kept from the store,
    /// so a stale copy from the app cannot roll them back.
    pub fn update_settings(&self, settings: AppSettings) {
        let current = self.settings.get();
        let settings = AppSettings {
            volume: current.volume,
            shuffle: current.shuffle,
            repeat: current.repeat,
            drm_verified_user: current.drm_verified_user,
            ..settings
        };
        let old = self.settings.set(settings.clone());
        self.apply_settings(&old, &settings);
    }

    /// Debug menu: forget that this account played, re-arming DRM detection.
    pub fn reset_drm_verification(&self) {
        self.settings.update(|s| s.drm_verified_user.clear());
    }

    pub fn feature_flags(&self) -> Vec<FeatureFlagInfo> {
        self.settings.get().feature_flags()
    }

    pub async fn clear_cache(&self) {
        let api = Arc::clone(&self.api);
        let librespot_cache = self.librespot_cache.clone();
        let _ = self
            .spawn(async move {
                api.clear_user_cache().await;
                riff_player::clear_audio_cache(&librespot_cache);
                Ok(())
            })
            .await;
    }

    // Playback

    pub fn playback(&self) -> PlaybackSnapshot {
        self.playback.snapshot()
    }

    pub fn queue(&self) -> QueueSnapshot {
        self.playback.queue()
    }

    /// Play `track_ids` (already returned by the core) as the context
    /// `source`, starting at `start_id`.
    pub fn play_tracks(
        &self,
        source: Option<PlaySource>,
        track_ids: Vec<String>,
        total: Option<u32>,
        start_id: Option<String>,
        shuffle: Option<bool>,
    ) {
        let tracks = self.lookup(&track_ids);
        if let Some(shuffle) = shuffle {
            self.settings.update(|s| s.shuffle = shuffle);
        }
        self.playback.send(Msg::PlayContext {
            source: source.map(SongsSource::from),
            tracks,
            total: total.map(|t| t as usize),
            start: start_id,
            shuffle,
        });
    }

    /// Load a whole source and play it, for "Play" buttons on cards.
    pub async fn play_source(&self, source: PlaySource, shuffle: bool) -> Result<(), CoreError> {
        let api = Arc::clone(&self.api);
        let songs: SongsSource = source.clone().into();
        let (tracks, total) = self
            .spawn(async move {
                let l = load(Priority::Hero);
                let page = match &songs {
                    SongsSource::Playlist(id) => {
                        api.get_playlist_tracks(id, 0, playback::PAGE_SIZE, l)
                            .await?
                    }
                    SongsSource::Album(id) => {
                        api.get_album_tracks(id, 0, playback::PAGE_SIZE, l).await?
                    }
                    SongsSource::SavedTracks => {
                        api.get_saved_tracks(0, playback::PAGE_SIZE, l).await?
                    }
                    SongsSource::Artist(id) => {
                        let tracks = api.get_artist_top_tracks(id, l).await?;
                        riff_api::models::Page {
                            total: Some(tracks.len()),
                            items: tracks,
                            offset: Some(0),
                            next_cursor: None,
                        }
                    }
                    SongsSource::Radio(id) => {
                        let tracks = api.get_track_radio(id, l).await?;
                        riff_api::models::Page {
                            total: Some(tracks.len()),
                            items: tracks,
                            offset: Some(0),
                            next_cursor: None,
                        }
                    }
                    SongsSource::Search(_) => riff_api::models::Page::default(),
                };
                Ok((page.items, page.total))
            })
            .await?;
        self.register(&tracks);
        self.settings.update(|s| s.shuffle = shuffle);
        self.playback.send(Msg::PlayContext {
            source: Some(source.into()),
            tracks,
            total,
            start: None,
            shuffle: Some(shuffle),
        });
        Ok(())
    }

    pub fn toggle_play(&self) {
        self.playback.send(Msg::TogglePlay);
    }

    pub fn play(&self) {
        self.playback.send(Msg::Play);
    }

    pub fn pause(&self) {
        self.playback.send(Msg::Pause);
    }

    pub fn stop(&self) {
        self.playback.send(Msg::Stop);
    }

    pub fn next(&self) {
        self.playback.send(Msg::Next);
    }

    pub fn previous(&self) {
        self.playback.send(Msg::Previous);
    }

    pub fn seek(&self, position_ms: u32) {
        self.playback.send(Msg::Seek(position_ms));
    }

    pub fn seek_by(&self, delta_ms: i32) {
        self.playback.send(Msg::SeekBy(delta_ms));
    }

    pub fn set_volume(&self, volume: f64) {
        self.settings.update(|s| s.volume = volume.clamp(0.0, 1.0));
        self.playback.send(Msg::SetVolume(volume));
    }

    pub fn adjust_volume(&self, delta: f64) {
        self.playback.send(Msg::AdjustVolume(delta));
    }

    pub fn toggle_mute(&self) {
        self.playback.send(Msg::ToggleMute);
    }

    pub fn set_shuffle(&self, shuffle: bool) {
        self.settings.update(|s| s.shuffle = shuffle);
        self.playback.send(Msg::SetShuffle(shuffle));
    }

    pub fn toggle_shuffle(&self) {
        let shuffle = !self.playback.snapshot().shuffle;
        self.set_shuffle(shuffle);
    }

    pub fn set_repeat(&self, repeat: Repeat) {
        self.settings.update(|s| s.repeat = repeat);
        self.playback.send(Msg::SetRepeat(repeat.into()));
    }

    pub fn cycle_repeat(&self) {
        let next = match self.playback.snapshot().repeat {
            Repeat::Off => Repeat::Context,
            Repeat::Context => Repeat::Track,
            Repeat::Track => Repeat::Off,
        };
        self.set_repeat(next);
    }

    /// Add tracks to the "next in queue" list.
    pub fn enqueue(&self, track_ids: Vec<String>) {
        let tracks = self.lookup(&track_ids);
        if !tracks.is_empty() {
            let count = tracks.len();
            self.playback.send(Msg::Enqueue(tracks));
            self.listener.on_event(CoreEvent::Notification {
                message: if count == 1 {
                    "Added to queue".into()
                } else {
                    format!("Added {count} tracks to queue")
                },
            });
        }
    }

    pub fn play_queue_entry(&self, key: String) {
        if let Some(key) = parse_key(&key) {
            self.playback.send(Msg::PlayEntry(key));
        }
    }

    pub fn remove_queue_entries(&self, keys: Vec<String>) {
        let keys: Vec<EntryKey> = keys.iter().filter_map(|k| parse_key(k)).collect();
        self.playback.send(Msg::Remove(keys));
    }

    pub fn clear_queued(&self) {
        self.playback.send(Msg::ClearQueued);
    }

    pub fn move_queue_entry(&self, key: String, target: String, after: bool) {
        if let (Some(key), Some(target)) = (parse_key(&key), parse_key(&target)) {
            self.playback.send(Msg::MoveEntry { key, target, after });
        }
    }

    // Spotify Connect

    /// Devices on the account that Riff could play on.
    pub async fn devices(&self) -> Result<Vec<DeviceInfo>, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let devices = api
                .get_devices(Load::new(LoadPriority::Transport, current_epoch()))
                .await?;
            Ok(devices.iter().map(DeviceInfo::from).collect())
        })
        .await
    }

    /// Move playback to a Connect device, or back to this device with `None`.
    pub fn switch_device(&self, device_id: Option<String>) {
        self.playback.send(Msg::SwitchDevice(device_id));
    }

    // Library and catalog

    pub async fn current_user(&self) -> Result<UserInfo, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move { Ok(UserInfo::from(&api.get_current_user(visible()).await?)) })
            .await
    }

    pub async fn user(&self, id: String) -> Result<UserInfo, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move { Ok(UserInfo::from(&api.get_user(&id, visible()).await?)) })
            .await
    }

    pub async fn user_playlists(
        &self,
        id: String,
        offset: u32,
        limit: u32,
    ) -> Result<PlaylistPage, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let page = api
                .get_user_playlists(&id, offset as usize, limit as usize, visible())
                .await?;
            Ok(PlaylistPage {
                items: page.items.iter().map(PlaylistInfo::from).collect(),
                offset,
                total: page.total.map(|t| t as u32),
            })
        })
        .await
    }

    pub async fn saved_albums(&self, offset: u32, limit: u32) -> Result<AlbumPage, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let page = api
                .get_saved_albums(offset as usize, limit as usize, visible())
                .await?;
            Ok(AlbumPage {
                items: page.items.iter().map(AlbumInfo::from).collect(),
                offset,
                total: page.total.map(|t| t as u32),
            })
        })
        .await
    }

    pub async fn saved_playlists(
        &self,
        offset: u32,
        limit: u32,
    ) -> Result<PlaylistPage, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let page = api
                .get_saved_playlists(offset as usize, limit as usize, visible())
                .await?;
            Ok(PlaylistPage {
                items: page.items.iter().map(PlaylistInfo::from).collect(),
                offset,
                total: page.total.map(|t| t as u32),
            })
        })
        .await
    }

    pub async fn saved_tracks(&self, offset: u32, limit: u32) -> Result<TrackPage, CoreError> {
        let api = Arc::clone(&self.api);
        let page = self
            .spawn(async move {
                Ok(api
                    .get_saved_tracks(offset as usize, limit as usize, visible())
                    .await?)
            })
            .await?;
        Ok(TrackPage {
            items: self.infos(&page.items),
            offset,
            total: page.total.map(|t| t as u32),
        })
    }

    pub async fn followed_artists(
        &self,
        after: Option<String>,
        limit: u32,
    ) -> Result<ArtistPage, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let (artists, next) = api
                .get_followed_artists(after.as_deref(), limit as usize, visible())
                .await?;
            Ok(ArtistPage {
                items: artists.iter().map(ArtistInfo::from).collect(),
                next_cursor: next,
            })
        })
        .await
    }

    /// The playlist library in folders, as the official clients show it.
    pub async fn library_tree(&self) -> Result<Vec<LibraryEntry>, CoreError> {
        let api = Arc::clone(&self.api);
        let user = self.user_id()?;
        self.spawn(async move {
            let nodes = api.get_library_tree(&user, visible()).await?;
            let mut out = vec![];
            flatten_library(&nodes, 0, &mut out);
            Ok(out)
        })
        .await
    }

    pub async fn album(&self, id: String) -> Result<AlbumInfo, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let mut album = AlbumInfo::from(&api.get_album(&id, load(Priority::Hero)).await?);
            if album.saved.is_none() {
                album.saved = api.check_saved_album(&id, visible()).await.ok();
            }
            Ok(album)
        })
        .await
    }

    pub async fn album_tracks(
        &self,
        id: String,
        offset: u32,
        limit: u32,
    ) -> Result<TrackPage, CoreError> {
        let api = Arc::clone(&self.api);
        let page = self
            .spawn(async move {
                Ok(api
                    .get_album_tracks(&id, offset as usize, limit as usize, visible())
                    .await?)
            })
            .await?;
        Ok(TrackPage {
            items: self.infos(&page.items),
            offset,
            total: page.total.map(|t| t as u32),
        })
    }

    pub async fn playlist(&self, id: String) -> Result<PlaylistInfo, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            Ok(PlaylistInfo::from(
                &api.get_playlist(&id, load(Priority::Hero)).await?,
            ))
        })
        .await
    }

    pub async fn playlist_tracks(
        &self,
        id: String,
        offset: u32,
        limit: u32,
    ) -> Result<TrackPage, CoreError> {
        let api = Arc::clone(&self.api);
        let page = self
            .spawn(async move {
                Ok(api
                    .get_playlist_tracks(&id, offset as usize, limit as usize, visible())
                    .await?)
            })
            .await?;
        Ok(TrackPage {
            items: self.infos(&page.items),
            offset,
            total: page.total.map(|t| t as u32),
        })
    }

    /// Tracks recommended for a playlist, skipping the ones it already has.
    pub async fn playlist_suggestions(
        &self,
        id: String,
        skip: Vec<String>,
        count: u32,
    ) -> Result<Vec<TrackInfo>, CoreError> {
        let api = Arc::clone(&self.api);
        let tracks = self
            .spawn(async move {
                Ok(api
                    .get_playlist_suggestions(&id, skip, count as usize, visible())
                    .await?)
            })
            .await?;
        Ok(self.infos(&tracks))
    }

    pub async fn artist(&self, id: String) -> Result<ArtistInfo, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let mut artist = ArtistInfo::from(&api.get_artist(&id, load(Priority::Hero)).await?);
            if artist.following.is_none() {
                artist.following = api.check_following_artist(&id, visible()).await.ok();
            }
            Ok(artist)
        })
        .await
    }

    pub async fn artist_top_tracks(&self, id: String) -> Result<Vec<TrackInfo>, CoreError> {
        let api = Arc::clone(&self.api);
        let tracks = self
            .spawn(async move { Ok(api.get_artist_top_tracks(&id, visible()).await?) })
            .await?;
        Ok(self.infos(&tracks))
    }

    pub async fn artist_albums(
        &self,
        id: String,
        offset: u32,
        limit: u32,
    ) -> Result<AlbumPage, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let page = api
                .get_artist_albums(&id, offset as usize, limit as usize, visible())
                .await?;
            Ok(AlbumPage {
                items: page.items.iter().map(AlbumInfo::from).collect(),
                offset,
                total: page.total.map(|t| t as u32),
            })
        })
        .await
    }

    pub async fn search(&self, query: String, limit: u32) -> Result<SearchResult, CoreError> {
        let api = Arc::clone(&self.api);
        let results = self
            .spawn(async move { Ok(api.search(&query, 0, limit as usize, visible()).await?) })
            .await?;
        Ok(self.search_result(results))
    }

    pub async fn search_scoped(
        &self,
        query: String,
        scope: SearchScope,
        offset: u32,
        limit: u32,
    ) -> Result<SearchResult, CoreError> {
        let api = Arc::clone(&self.api);
        let kind = match scope {
            SearchScope::Tracks => SearchType::Track,
            SearchScope::Albums => SearchType::Album,
            SearchScope::Artists => SearchType::Artist,
            SearchScope::Playlists => SearchType::Playlist,
        };
        let results = self
            .spawn(async move {
                Ok(api
                    .search_scoped(&query, kind, offset as usize, limit as usize, visible())
                    .await?)
            })
            .await?;
        Ok(self.search_result(results))
    }

    pub async fn track(&self, id: String) -> Result<TrackInfo, CoreError> {
        let api = Arc::clone(&self.api);
        let track = self
            .spawn(async move { Ok(api.get_track(&id, visible()).await?) })
            .await?;
        Ok(self.infos(std::slice::from_ref(&track)).remove(0))
    }

    pub async fn lyrics(&self, track_id: String) -> Result<LyricsInfo, CoreError> {
        let track =
            self.lookup(std::slice::from_ref(&track_id))
                .pop()
                .ok_or(CoreError::Failed {
                    reason: "unknown track".into(),
                })?;
        let api = Arc::clone(&self.api);
        self.spawn(async move { Ok(LyricsInfo::from(&api.get_lyrics(&track, visible()).await?)) })
            .await
    }

    pub async fn track_radio(&self, track_id: String) -> Result<Vec<TrackInfo>, CoreError> {
        let api = Arc::clone(&self.api);
        let tracks = self
            .spawn(async move { Ok(api.get_track_radio(&track_id, visible()).await?) })
            .await?;
        Ok(self.infos(&tracks))
    }

    pub async fn recently_played(&self, limit: u32) -> Result<Vec<TrackInfo>, CoreError> {
        let api = Arc::clone(&self.api);
        let items = self
            .spawn(async move { Ok(api.get_recently_played(limit as usize, visible()).await?) })
            .await?;
        let mut seen = std::collections::HashSet::new();
        let tracks: Vec<Track> = items
            .into_iter()
            .map(|i| i.track)
            .filter(|t| seen.insert(t.rri.id.clone()))
            .collect();
        Ok(self.infos(&tracks))
    }

    /// Albums, playlists and artists the user played from lately, newest
    /// first, resolved from Spotify's listening history.
    pub async fn recent_contexts(&self, limit: u32) -> Result<Vec<RecentItem>, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            let history = api.get_recently_played(50, visible()).await?;
            let mut seen = std::collections::HashSet::new();
            let mut items = vec![];
            for uri in history.iter().filter_map(|h| h.context_uri.as_deref()) {
                if items.len() >= limit as usize {
                    break;
                }
                if !seen.insert(uri.to_string()) {
                    continue;
                }
                let mut parts = uri.split(':').skip(1);
                let (Some(kind), Some(id)) = (parts.next(), parts.next()) else {
                    continue;
                };
                let item =
                    match kind {
                        "album" => {
                            api.get_album(id, visible())
                                .await
                                .ok()
                                .map(|a| RecentItem::Album {
                                    album: AlbumInfo::from(&a),
                                })
                        }
                        "playlist" => api.get_playlist(id, visible()).await.ok().map(|p| {
                            RecentItem::Playlist {
                                playlist: PlaylistInfo::from(&p),
                            }
                        }),
                        "artist" => {
                            api.get_artist(id, visible())
                                .await
                                .ok()
                                .map(|a| RecentItem::Artist {
                                    artist: ArtistInfo::from(&a),
                                })
                        }
                        _ => None,
                    };
                items.extend(item);
            }
            Ok(items)
        })
        .await
    }

    pub async fn top_artists(&self, limit: u32) -> Result<Vec<ArtistInfo>, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            Ok(api
                .get_top_artists(limit as usize, visible())
                .await?
                .iter()
                .map(ArtistInfo::from)
                .collect())
        })
        .await
    }

    pub async fn category_playlists(
        &self,
        category: String,
        limit: u32,
    ) -> Result<Vec<PlaylistInfo>, CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            Ok(api
                .get_category_playlists(&category, limit as usize, visible())
                .await?
                .iter()
                .map(PlaylistInfo::from)
                .collect())
        })
        .await
    }

    /// Search playlists by name, used for the home page shelves.
    pub async fn find_playlists(
        &self,
        query: String,
        limit: u32,
    ) -> Result<Vec<PlaylistInfo>, CoreError> {
        let results = self
            .search_scoped(query, SearchScope::Playlists, 0, limit)
            .await?;
        Ok(results.playlists)
    }

    /// Artwork bytes (JPEG or PNG) for the app to decode, cached in memory and
    /// on disk by the api layer.
    pub async fn image(&self, url: String, size: u32, priority: Priority) -> Option<Vec<u8>> {
        let api = Arc::clone(&self.api);
        let size = size.min(i32::MAX as u32) as i32;
        self.runtime
            .spawn(async move {
                api.load_image(&url, size, size, load(priority))
                    .await
                    .map(|bytes| bytes.to_vec())
            })
            .await
            .ok()
            .flatten()
    }

    // Library edits

    pub async fn set_tracks_saved(&self, ids: Vec<String>, saved: bool) -> Result<(), CoreError> {
        let api = Arc::clone(&self.api);
        {
            let mut registry = self.tracks.lock().unwrap();
            for id in &ids {
                if let Some(track) = registry.get_mut(id) {
                    track.saved = Some(saved);
                }
            }
        }
        self.spawn(async move {
            if saved {
                api.save_tracks(ids).await?;
            } else {
                api.remove_tracks(ids).await?;
            }
            Ok(())
        })
        .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(())
    }

    pub async fn set_album_saved(&self, id: String, saved: bool) -> Result<(), CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            if saved {
                api.save_albums(&id).await?;
            } else {
                api.remove_albums(&id).await?;
            }
            Ok(())
        })
        .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(())
    }

    pub async fn set_artist_followed(&self, id: String, follow: bool) -> Result<(), CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            if follow {
                api.follow_artists(&id).await?;
            } else {
                api.unfollow_artists(&id).await?;
            }
            Ok(())
        })
        .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(())
    }

    pub async fn set_playlist_followed(&self, id: String, follow: bool) -> Result<(), CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            if follow {
                api.follow_playlist(&id).await?;
            } else {
                api.unfollow_playlist(&id).await?;
            }
            Ok(())
        })
        .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(())
    }

    pub async fn create_playlist(
        &self,
        name: String,
        description: Option<String>,
        public: bool,
    ) -> Result<PlaylistInfo, CoreError> {
        let api = Arc::clone(&self.api);
        let user = self.user_id()?;
        let playlist = self
            .spawn(async move {
                Ok(PlaylistInfo::from(
                    &api.create_playlist(&user, &name, description.as_deref(), public)
                        .await?,
                ))
            })
            .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(playlist)
    }

    pub async fn update_playlist(
        &self,
        id: String,
        name: String,
        description: Option<String>,
        public: Option<bool>,
    ) -> Result<(), CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            api.update_playlist_details(&id, &name, description.as_deref(), public)
                .await?;
            Ok(())
        })
        .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(())
    }

    /// Upload a JPEG cover that the app already cropped to a square.
    pub async fn upload_playlist_cover(&self, id: String, jpeg: Vec<u8>) -> Result<(), CoreError> {
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            api.upload_playlist_cover(&id, jpeg, visible()).await?;
            Ok(())
        })
        .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(())
    }

    /// Returns how many tracks were actually added (duplicates are skipped).
    pub async fn add_to_playlist(
        &self,
        id: String,
        track_ids: Vec<String>,
    ) -> Result<u32, CoreError> {
        let uris: Vec<String> = track_ids
            .iter()
            .map(|id| format!("spotify:track:{id}"))
            .collect();
        let api = Arc::clone(&self.api);
        let added = self
            .spawn(async move { Ok(api.add_to_playlist(&id, uris, visible()).await?) })
            .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(added.len() as u32)
    }

    pub async fn remove_from_playlist(
        &self,
        id: String,
        track_ids: Vec<String>,
    ) -> Result<(), CoreError> {
        let uris: Vec<String> = track_ids
            .iter()
            .map(|id| format!("spotify:track:{id}"))
            .collect();
        let api = Arc::clone(&self.api);
        self.spawn(async move {
            api.remove_from_playlist(&id, uris).await?;
            Ok(())
        })
        .await?;
        self.listener.on_event(CoreEvent::LibraryChanged);
        Ok(())
    }

    /// Flush caches to their budgets. Call before quitting.
    pub fn shutdown(&self) {
        let api = Arc::clone(&self.api);
        let _ = self
            .runtime
            .block_on(async move { api.run_cache_maintenance().await });
    }
}

impl RiffCore {
    fn search_result(&self, results: riff_api::models::SearchResults) -> SearchResult {
        SearchResult {
            tracks: self.infos(&results.tracks.items),
            albums: results.albums.items.iter().map(AlbumInfo::from).collect(),
            artists: results.artists.items.iter().map(ArtistInfo::from).collect(),
            playlists: results
                .playlists
                .items
                .iter()
                .map(PlaylistInfo::from)
                .collect(),
            total_tracks: results.tracks.total.map(|t| t as u32),
            total_albums: results.albums.total.map(|t| t as u32),
            total_artists: results.artists.total.map(|t| t as u32),
            total_playlists: results.playlists.total.map(|t| t as u32),
        }
    }
}

/// Parse a Spotify link (open.spotify.com URL or `spotify:` URI) into a
/// `(kind, id)` pair, e.g. `("album", "4aawyAB9vmqN3uQ7FjRGTy")`.
#[uniffi::export]
pub fn parse_spotify_link(link: String) -> Option<SpotifyLink> {
    let link = link.trim();
    let (kind, id) = if let Some(rest) = link.strip_prefix("spotify:") {
        let mut parts = rest.split(':');
        (parts.next()?.to_string(), parts.next()?.to_string())
    } else {
        let url = Url::parse(link).ok()?;
        if url.host_str()? != "open.spotify.com" {
            return None;
        }
        let mut segments: Vec<&str> = url.path_segments()?.filter(|s| !s.is_empty()).collect();
        // Localised links look like /intl-de/track/<id>.
        if segments.first().is_some_and(|s| s.starts_with("intl-")) {
            segments.remove(0);
        }
        (segments.first()?.to_string(), segments.get(1)?.to_string())
    };
    let kind = match kind.as_str() {
        "track" => LinkKind::Track,
        "album" => LinkKind::Album,
        "artist" => LinkKind::Artist,
        "playlist" => LinkKind::Playlist,
        "user" => LinkKind::User,
        _ => return None,
    };
    (!id.is_empty()).then_some(SpotifyLink { kind, id })
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinkKind {
    Track,
    Album,
    Artist,
    Playlist,
    User,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct SpotifyLink {
    pub kind: LinkKind,
    pub id: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_spotify_links() {
        let link = parse_spotify_link("https://open.spotify.com/intl-de/album/abc?si=1".into());
        assert_eq!(
            link,
            Some(SpotifyLink {
                kind: LinkKind::Album,
                id: "abc".into()
            })
        );
        assert_eq!(
            parse_spotify_link("spotify:track:xyz".into()),
            Some(SpotifyLink {
                kind: LinkKind::Track,
                id: "xyz".into()
            })
        );
        assert_eq!(
            parse_spotify_link("https://example.com/album/abc".into()),
            None
        );
        assert_eq!(parse_spotify_link("spotify:show:abc".into()), None);
    }
}
