//! GTK side of the player: forwards [`riff_player`] feedback into app actions
//! and reads its settings from GSettings.

use futures::channel::mpsc::UnboundedSender;
use std::path::PathBuf;
use std::sync::Arc;
use url::Url;

use gettextrs::gettext;

use crate::app::state::{LoginAction, PlaybackAction};
use crate::app::AppAction;
use crate::settings::RiffSettings;
use riff_auth::{SessionTokens, TokenStore};
pub use riff_player::{
    AudioBackend, Command, PlayerDelegate, SpotifyError, SpotifyPlayerSettings, VolumeCurveType,
};

#[derive(Clone)]
pub(crate) struct AppPlayerDelegate {
    sender: UnboundedSender<AppAction>,
}

impl AppPlayerDelegate {
    fn new(sender: UnboundedSender<AppAction>) -> Self {
        Self { sender }
    }

    fn send(&self, action: AppAction) {
        self.sender.unbounded_send(action).unwrap();
    }
}

impl PlayerDelegate for AppPlayerDelegate {
    fn end_of_track_reached(&self) {
        self.send(PlaybackAction::TrackEnded.into())
    }

    fn skip_track(&self) {
        self.send(PlaybackAction::Next.into())
    }

    fn stop_playback(&self) {
        self.send(PlaybackAction::Stop.into())
    }

    fn token_login_successful(&self, username: String) {
        self.send(LoginAction::SetLoginSuccess(username).into())
    }

    fn refresh_successful(&self) {
        self.send(LoginAction::TokenRefreshed.into())
    }

    fn set_explicit_filter_locked(&self, locked: bool) {
        self.send(PlaybackAction::SetExplicitFilterLocked(locked).into())
    }

    fn set_skip_explicit(&self, skip: bool) {
        self.send(PlaybackAction::SetSkipExplicit(skip).into())
    }

    fn report_error(&self, error: SpotifyError) {
        self.send(match error {
            SpotifyError::NotPremium => LoginAction::SetNotPremium.into(),
            SpotifyError::LoginFailed => LoginAction::SetLoginFailure.into(),
            SpotifyError::LoggedOut => LoginAction::Logout.into(),
            SpotifyError::PlaybackDrmBlocked => AppAction::ShowDrmBlockedDialog,
            SpotifyError::PlaybackTemporarilyUnavailable => {
                AppAction::ShowNotification(gettext("Playback is Temporarily Unavailable"))
            }
            _ => AppAction::ShowNotification(format!("{error}")),
        })
    }

    fn notify_playback_state(&self, position: u32) {
        self.send(PlaybackAction::SyncSeek(position).into())
    }

    fn preload_next_track(&self) {
        self.send(PlaybackAction::PreloadNext.into())
    }

    fn login_challenge_started(&self, url: Url) {
        self.send(LoginAction::OpenLoginUrl(url).into())
    }

    fn set_connection_lost(&self, lost: bool) {
        self.send(AppAction::SetConnectionLost(lost))
    }

    fn load_settings(&self) -> Option<SpotifyPlayerSettings> {
        RiffSettings::new_from_gsettings().map(|settings| settings.player_settings)
    }

    fn drm_verified_user(&self) -> String {
        crate::settings::drm_verified_user()
    }

    fn set_drm_verified_user(&self, user_id: &str) {
        crate::settings::set_drm_verified_user(user_id);
    }

    fn clear_drm_verified_user(&self) {
        crate::settings::clear_drm_verified_user();
    }

    fn librespot_cache_root(&self) -> PathBuf {
        librespot_cache_root()
    }

    fn audio_cache_size_limit_bytes(&self) -> u64 {
        crate::settings::audio_cache_size_limit_bytes()
    }
}

fn librespot_cache_root() -> PathBuf {
    glib::user_cache_dir().join("riff").join("librespot")
}

pub fn clear_audio_cache() {
    riff_player::clear_audio_cache(&librespot_cache_root());
}

pub fn start_player_service(
    player_settings: SpotifyPlayerSettings,
    appaction_sender: UnboundedSender<AppAction>,
    token_store: TokenStore,
    session_tokens: SessionTokens,
) -> UnboundedSender<Command> {
    riff_player::start_player_service(
        player_settings,
        Arc::new(AppPlayerDelegate::new(appaction_sender)),
        token_store,
        session_tokens,
    )
}
