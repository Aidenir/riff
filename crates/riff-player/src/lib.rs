//! librespot session and local playback management, shared by the GTK and
//! macOS clients.
//!
//! The player runs on its own thread with its own tokio runtime. Frontends
//! send it [`Command`]s and receive feedback through a [`PlayerDelegate`].

#[macro_use]
extern crate log;

use futures::channel::mpsc::{unbounded, UnboundedReceiver, UnboundedSender};
use librespot::core::SpotifyUri;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::task;
use url::Url;

use riff_auth::{SessionTokens, TokenStore};
#[allow(clippy::module_inception)]
mod player;
pub use player::*;

#[derive(Debug, Clone)]
pub enum Command {
    Restore,
    InitLogin,
    CompleteLogin,
    Logout,
    PlayerLoad {
        track: SpotifyUri,
        resume: bool,
    },
    PlayerResume,
    PlayerPause,
    PlayerStop,
    PlayerSeek(u32),
    PlayerSetVolume(f64),
    PlayerPreload(SpotifyUri),
    ReloadSettings,
    SetEqualizer {
        bands: [f64; 10],
    },
    SetMono {
        enabled: bool,
    },
    SetPan {
        pan: f64,
    },
    SetPitch {
        cents: f64,
    },
    // Rebuild the librespot session and player if the current session has
    // died. librespot sessions are single-use: once the connection drops they
    // are invalidated forever and a brand new session must be created.
    // Requested by the session watchdog, the token refresh loop, or the
    // player event handler when it detects a dead session. No-op when the
    // session is healthy.
    ReconnectSession,
    // The player reported a track it could not load (librespot's Unavailable
    // event). The command loop decides whether that means "reconnect and
    // retry" (dead session) or "skip the track" (explicit-filtered or
    // genuinely unavailable); the event listener can't make that call because
    // it only holds a snapshot of the session that may have been replaced.
    TrackUnavailable,
    // First successful playback since login; persist the account as known-good.
    MarkPlaybackVerified,
    // Re-query the account's explicit content filter (e.g. after a track was
    // rejected with ExplicitContentFiltered) and sync Riff's filter state.
    RecheckExplicitFilter,
    // Carries the result of an asynchronous explicit-filter re-check back into
    // the command loop so the player can update its cached state.
    ExplicitFilterRechecked {
        filter_enabled: bool,
        filter_locked: bool,
    },
    // Dev tools only: destroy the librespot Player (the audio engine) while
    // leaving the session alone. The health watchdog then rebuilds it.
    #[cfg(debug_assertions)]
    DevKillPlayer,
    // Dev tools only: shut down the librespot Session (the connection to
    // Spotify) while leaving the player alone. The health watchdog then
    // reconnects, exercising the real reconnect path.
    #[cfg(debug_assertions)]
    DevKillSession,
    // Dev tools only: backdate the cached OAuth token and force a refresh,
    // exercising the token-refresh flow on demand.
    #[cfg(debug_assertions)]
    DevExpireToken,
    // Dev tools only: clear the verified marker and re-arm DRM detection.
    #[cfg(debug_assertions)]
    DevResetDrmVerification,
    // Dev tools only: simulate one "track failed to load" event on a healthy
    // session, feeding the failure counter.
    #[cfg(debug_assertions)]
    DevSimulateTrackUnavailable,
}

/// Receives feedback from the player and supplies the settings it persists.
///
/// Called from the player thread, so implementations forward to their own
/// main loop instead of touching UI state directly.
pub trait PlayerDelegate: Send + Sync {
    fn end_of_track_reached(&self);
    fn skip_track(&self);
    fn stop_playback(&self);
    fn token_login_successful(&self, username: String);
    fn refresh_successful(&self);
    fn set_explicit_filter_locked(&self, locked: bool);
    fn set_skip_explicit(&self, skip: bool);
    fn report_error(&self, error: SpotifyError);
    fn notify_playback_state(&self, position: u32);
    fn preload_next_track(&self);
    fn login_challenge_started(&self, url: Url);
    fn set_connection_lost(&self, lost: bool);

    /// Current player settings, read again on [`Command::ReloadSettings`].
    fn load_settings(&self) -> Option<SpotifyPlayerSettings>;
    /// Account last seen playing audio, so DRM detection is skipped for it.
    fn drm_verified_user(&self) -> String;
    fn set_drm_verified_user(&self, user_id: &str);
    fn clear_drm_verified_user(&self);
    /// Directory holding librespot's credentials, volume and audio caches.
    fn librespot_cache_root(&self) -> PathBuf;
    fn audio_cache_size_limit_bytes(&self) -> u64;
}

// Two workers are plenty for one session; the default is one per core
#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn player_main(
    player_settings: SpotifyPlayerSettings,
    delegate: Arc<dyn PlayerDelegate>,
    token_store: TokenStore,
    session_tokens: SessionTokens,
    sender: UnboundedSender<Command>,
    receiver: UnboundedReceiver<Command>,
) {
    task::spawn(async move {
        let player = SpotifyPlayer::new(
            player_settings,
            delegate,
            token_store,
            session_tokens,
            sender,
        );
        player.start(receiver).await.unwrap();
    })
    .await
    .unwrap();
}

pub fn start_player_service(
    player_settings: SpotifyPlayerSettings,
    delegate: Arc<dyn PlayerDelegate>,
    token_store: TokenStore,
    session_tokens: SessionTokens,
) -> UnboundedSender<Command> {
    let (sender, receiver) = unbounded::<Command>();
    let sender_clone = sender.clone();
    std::thread::spawn(move || {
        player_main(
            player_settings,
            delegate,
            token_store,
            session_tokens,
            sender_clone,
            receiver,
        )
    });
    sender
}
