//! Spotify Connect: controlling another device through the Web API, the
//! macOS counterpart of `src/connect` in the GTK app.

use std::sync::Arc;

use riff_api::models::Track;
use riff_api::{ApiService, DomainError, Load, LoadPriority};
use tokio::sync::mpsc;

use crate::models::CoreEvent;
use crate::playback::{Msg, RemoteOp};
use crate::Listener;

fn transport() -> Load {
    Load::new(LoadPriority::Transport, crate::current_epoch())
}

/// Run device commands in order, stopping at the first failure.
pub async fn run(
    api: Arc<ApiService>,
    ops: Vec<(String, RemoteOp)>,
    sender: mpsc::UnboundedSender<Msg>,
    listener: Arc<dyn Listener>,
) {
    for (device, op) in ops {
        let result = match op {
            RemoteOp::PlayContext { uri, offset } => {
                api.player_play_in_context(&device, &uri, offset).await
            }
            RemoteOp::PlayUris { uris, offset } => {
                api.player_play_uris(&device, uris, offset).await
            }
            RemoteOp::Resume => api.player_resume(&device).await,
            RemoteOp::Pause => api.player_pause(&device).await,
            RemoteOp::Seek(position) => api.player_seek(&device, position).await,
            RemoteOp::Volume(volume) => api.player_volume(&device, volume).await,
            RemoteOp::Shuffle(shuffle) => api.player_shuffle(&device, shuffle).await,
            RemoteOp::Repeat(mode) => api.player_repeat(&device, mode).await,
        };
        match result {
            Ok(()) => {}
            Err(DomainError::NotFound { .. }) => {
                lost(&sender, &listener);
                return;
            }
            Err(DomainError::RateLimited { .. }) => {
                listener.on_event(CoreEvent::Notification {
                    message: "Rate limited by Spotify. Please wait a moment and try again.".into(),
                });
                return;
            }
            Err(e) => {
                warn!("connect: command failed: {e}");
                return;
            }
        }
    }
}

fn lost(sender: &mpsc::UnboundedSender<Msg>, listener: &Arc<dyn Listener>) {
    let _ = sender.send(Msg::DeviceLost);
    listener.on_event(CoreEvent::Notification {
        message: "Connection to device lost!".into(),
    });
}

/// Read the device's state, with its queue when that changed since `last`.
pub async fn poll(
    api: Arc<ApiService>,
    last: Option<Vec<String>>,
    sender: mpsc::UnboundedSender<Msg>,
) {
    let state = match api.get_player_state(transport()).await {
        Ok(state) => state,
        Err(e) => {
            debug!("connect: device state unavailable: {e}");
            let _ = sender.send(Msg::DeviceLost);
            return;
        }
    };
    let queue = api.get_player_queue(transport()).await.ok().map(|queue| {
        queue
            .currently_playing
            .into_iter()
            .chain(queue.items)
            .collect::<Vec<Track>>()
    });
    let ids: Option<Vec<String>> = queue
        .as_ref()
        .map(|tracks| tracks.iter().map(|t| t.rri.id.clone()).collect());
    let changed = ids.is_some() && ids != last;
    let _ = sender.send(Msg::RemoteState(state, queue.filter(|_| changed)));
}
