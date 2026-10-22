//! Playback controller for the macOS client.
//!
//! Mirrors the GTK `PlaybackState` reducer (`src/app/state/playback_state.rs`)
//! on top of the shared [`PlayQueue`]: it decides what plays next, drives the
//! librespot player through [`Command`]s and publishes snapshots to Swift.
//! Everything runs on one task, fed by [`Msg`]s, so no locking is needed
//! around the queue itself.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures::channel::mpsc::UnboundedSender;
use librespot::core::spotify_id::SpotifyId;
use librespot::core::SpotifyUri;
use riff_api::models::{ContentRating, Page, PlayerState, Track};
use riff_api::{ApiService, DomainError, Load, LoadPriority, RepeatMode};
use riff_core::play_queue::{EntryKey, PageRequest, PlayQueue, SongsSource, CONTEXT_PAGE_SIZE};
use riff_player::Command;
use tokio::sync::mpsc;

use crate::models::{
    CoreEvent, PlaySource, PlaybackSnapshot, QueueEntry, QueueSnapshot, TrackInfo,
};
use crate::{connect, Listener};

// An unanswered page request is retried after this.
const RETRY_AFTER: Duration = Duration::from_secs(10);
// Restoring the last volume after unmuting with nothing remembered.
const UNMUTE_FALLBACK_VOLUME: f64 = 0.5;
// "Previous" restarts the track instead when it has played longer than this.
const RESTART_THRESHOLD_MS: u64 = 2000;

#[derive(Debug)]
pub enum Msg {
    /// Replace the context and start playing `start` (or the first track).
    PlayContext {
        source: Option<SongsSource>,
        tracks: Vec<Track>,
        total: Option<usize>,
        start: Option<String>,
        shuffle: Option<bool>,
    },
    TogglePlay,
    Play,
    Pause,
    Stop,
    Next,
    Previous,
    Seek(u32),
    SeekBy(i32),
    SetVolume(f64),
    AdjustVolume(f64),
    ToggleMute,
    SetShuffle(bool),
    SetRepeat(RepeatMode),
    Enqueue(Vec<Track>),
    PlayEntry(EntryKey),
    Remove(Vec<EntryKey>),
    ClearQueued,
    MoveEntry {
        key: EntryKey,
        target: EntryKey,
        after: bool,
    },
    SetSkipExplicit(bool),
    SetSeparation(u32),
    // Feedback from the librespot player.
    TrackEnded,
    PreloadNext,
    SyncPosition(u32),
    SkipTrack,
    StopPlayback,
    ExplicitFilterLocked(bool),
    PageLoaded(SongsSource, Page<Track>),
    // Spotify Connect.
    /// Play on a Connect device, or locally with `None`.
    SwitchDevice(Option<String>),
    /// Ask the active Connect device for its state.
    PollDevice,
    RemoteState(PlayerState, Option<Vec<Track>>),
    DeviceLost,
}

/// A Web API call controlling a Spotify Connect device.
#[derive(Debug, Clone, PartialEq)]
pub enum RemoteOp {
    PlayContext { uri: String, offset: usize },
    PlayUris { uris: Vec<String>, offset: usize },
    Resume,
    Pause,
    Seek(u32),
    Volume(u8),
    Shuffle(bool),
    Repeat(RepeatMode),
}

/// Where a playback command goes: the local librespot player or a device.
#[derive(Debug)]
pub enum Out {
    Local(Command),
    Remote(String, RemoteOp),
}

/// Playback position that keeps counting while playing.
struct Position {
    known: u64,
    resumed: Option<Instant>,
}

impl Position {
    fn current(&self) -> u64 {
        self.known + self.resumed.map_or(0, |at| at.elapsed().as_millis() as u64)
    }

    fn set(&mut self, position: u64, playing: bool) {
        self.known = position;
        self.resumed = playing.then(Instant::now);
    }

    fn pause(&mut self) {
        self.known = self.current();
        self.resumed = None;
    }

    fn resume(&mut self) {
        self.resumed = Some(Instant::now());
    }
}

struct Controller {
    queue: PlayQueue,
    repeat: RepeatMode,
    playing: bool,
    position: Position,
    volume: f64,
    muted_volume: Option<f64>,
    skip_explicit: bool,
    explicit_locked: bool,
    last_page_request: Option<(SongsSource, usize, Instant)>,
    // Effects collected while handling a message.
    commands: Vec<Out>,
    /// Spotify Connect device being controlled, `None` when playing here.
    device: Option<String>,
    last_remote_queue: Option<Vec<String>>,
    queue_changed: bool,
}

fn is_explicit(track: &Track) -> bool {
    matches!(track.content_rating, ContentRating::Explicit)
}

fn track_uri(id: &str) -> Option<SpotifyUri> {
    SpotifyId::from_base62(id)
        .ok()
        .map(|id| SpotifyUri::Track { id })
}

impl Controller {
    /// Routes a player command to librespot, or translates it into a Web
    /// API call when a Connect device is active (src/connect on Linux).
    fn send(&mut self, command: Command) {
        let Some(device) = self.device.clone() else {
            self.commands.push(Out::Local(command));
            return;
        };
        let op = match command {
            Command::PlayerLoad { .. } => Some(self.remote_load()),
            Command::PlayerResume => Some(RemoteOp::Resume),
            Command::PlayerPause | Command::PlayerStop => Some(RemoteOp::Pause),
            Command::PlayerSeek(position) => Some(RemoteOp::Seek(position)),
            Command::PlayerSetVolume(volume) => Some(RemoteOp::Volume((volume * 100.0) as u8)),
            _ => None,
        };
        if let Some(op) = op {
            self.commands.push(Out::Remote(device, op));
        }
    }

    fn send_remote(&mut self, op: RemoteOp) {
        if let Some(device) = self.device.clone() {
            self.commands.push(Out::Remote(device, op));
        }
    }

    /// Play the current track on the device, inside its album or playlist
    /// when it has one so the device can carry on by itself.
    fn remote_load(&self) -> RemoteOp {
        let context = self
            .queue
            .source()
            .and_then(|s| s.spotify_uri())
            .zip(self.queue.current_context_index())
            .filter(|_| !self.queue.is_shuffled());
        match context {
            Some((uri, offset)) => RemoteOp::PlayContext { uri, offset },
            None => RemoteOp::PlayUris {
                uris: self
                    .queue
                    .upcoming()
                    .iter()
                    .map(|t| format!("spotify:track:{}", t.rri.id))
                    .collect(),
                offset: 0,
            },
        }
    }

    fn switch_device(&mut self, device: Option<String>) {
        if device == self.device {
            return;
        }
        let position = self.position.current() as u32;
        let had_track = self.queue.current().is_some();
        match self.device.take() {
            Some(old) => self.commands.push(Out::Remote(old, RemoteOp::Pause)),
            None => self.commands.push(Out::Local(Command::PlayerStop)),
        }
        self.device = device;
        self.last_remote_queue = None;
        if had_track {
            self.load_current(self.playing);
            if position > 0 {
                self.send(Command::PlayerSeek(position));
            }
            if !self.playing {
                self.send(Command::PlayerPause);
            }
        }
    }

    /// Mirror what the device is doing, without sending anything back.
    fn apply_remote(&mut self, state: PlayerState, queue: Option<Vec<Track>>) {
        if let Some(tracks) = queue {
            self.last_remote_queue = Some(tracks.iter().map(|t| t.rri.id.clone()).collect());
            self.queue.set_context(None, tracks);
            self.queue_changed = true;
        }
        if let Some(id) = state.current_track_id.as_ref().map(|r| r.id.clone()) {
            if self.current_id().as_deref() != Some(id.as_str()) && self.queue.play_id(&id) {
                self.queue_changed = true;
            }
        }
        self.playing = state.is_playing;
        self.position
            .set(state.progress_ms as u64, state.is_playing);
        if self.repeat != state.repeat {
            self.repeat = state.repeat;
            self.queue_changed = true;
        }
        if self.queue.is_shuffled() != state.shuffle {
            self.queue.set_shuffled(state.shuffle);
            self.queue_changed = true;
        }
    }

    fn skipper(&self) -> impl Fn(&Track) -> bool {
        let skip_explicit = self.skip_explicit;
        move |track: &Track| !track.playable || (skip_explicit && is_explicit(track))
    }

    fn current_id(&self) -> Option<String> {
        self.queue.current().map(|t| t.rri.id.clone())
    }

    fn load_current(&mut self, resume: bool) {
        if let Some(track) = self.current_id().as_deref().and_then(track_uri) {
            self.send(Command::PlayerLoad { track, resume });
        }
    }

    fn started_playing(&mut self) {
        self.playing = true;
        self.position.set(0, true);
        self.queue_changed = true;
        self.load_current(true);
    }

    fn stop(&mut self) {
        self.queue.stop();
        self.playing = false;
        self.position.set(0, false);
        self.queue_changed = true;
        self.send(Command::PlayerStop);
    }

    fn play_next(&mut self, auto: bool) {
        let skip = self.skipper();
        if self.queue.advance(auto, self.repeat, skip).is_some() {
            self.started_playing();
        } else {
            self.stop();
        }
    }

    fn current_should_skip(&self) -> bool {
        self.queue.current().is_some_and(|t| (self.skipper())(t))
    }

    fn jumped(&mut self) {
        if self.current_should_skip() {
            self.play_next(false);
        } else {
            self.started_playing();
        }
    }

    fn skip_current_if_needed(&mut self) {
        if self.current_should_skip() {
            self.play_next(false);
        }
    }

    fn start_from_queue(&mut self) {
        if self.queue.current().is_none() && self.queue.has_next(self.repeat) {
            self.play_next(false);
        }
    }

    fn toggle_play(&mut self) -> Option<bool> {
        self.queue.current()?;
        self.playing = !self.playing;
        if self.playing {
            self.position.resume();
            self.send(Command::PlayerResume);
        } else {
            self.position.pause();
            self.send(Command::PlayerPause);
        }
        Some(self.playing)
    }

    fn set_volume(&mut self, volume: f64) {
        let volume = volume.clamp(0.0, 1.0);
        if self.volume != volume {
            self.volume = volume;
            self.send(Command::PlayerSetVolume(volume));
        }
    }

    fn seek_to(&mut self, position: u32) {
        self.position.set(position as u64, self.playing);
        self.send(Command::PlayerSeek(position));
    }

    fn previous(&mut self) {
        if self.queue.current().is_none() {
            return;
        }
        if self.position.current() <= RESTART_THRESHOLD_MS {
            let skip = self.skipper();
            if self.queue.go_back(self.repeat, skip).is_some() {
                self.started_playing();
                return;
            }
        }
        self.seek_to(0);
    }

    fn handle(&mut self, msg: Msg) {
        match msg {
            Msg::PlayContext {
                source,
                tracks,
                total,
                start,
                shuffle,
            } => {
                self.queue.clear();
                if let Some(shuffle) = shuffle {
                    self.queue.set_shuffled(shuffle);
                }
                match source {
                    Some(source) if source.is_paginated() => {
                        let page = Page {
                            items: tracks,
                            offset: Some(0),
                            total,
                            next_cursor: None,
                        };
                        self.queue.add_page(source, page);
                    }
                    source => self.queue.set_context(source, tracks),
                }
                self.queue_changed = true;
                let found = start.as_deref().is_some_and(|id| self.queue.play_id(id));
                if found {
                    self.jumped();
                } else {
                    self.play_next(false);
                }
            }
            Msg::TogglePlay => {
                if self.toggle_play().is_none() {
                    self.start_from_queue();
                }
            }
            Msg::Play => {
                if self.queue.current().is_none() {
                    self.start_from_queue();
                } else if !self.playing {
                    self.toggle_play();
                }
            }
            Msg::Pause => {
                if self.playing {
                    self.toggle_play();
                }
            }
            Msg::Stop => self.stop(),
            Msg::Next => self.play_next(false),
            Msg::TrackEnded => self.play_next(true),
            Msg::SkipTrack => self.play_next(false),
            Msg::StopPlayback => self.stop(),
            Msg::Previous => self.previous(),
            Msg::Seek(position) => self.seek_to(position),
            Msg::SeekBy(delta) => {
                let Some(duration) = self.queue.current().map(|t| t.duration_ms) else {
                    return;
                };
                let target = (self.position.current() as i64 + delta as i64).max(0);
                if target >= duration as i64 {
                    self.play_next(false);
                } else {
                    self.seek_to(target as u32);
                }
            }
            Msg::SyncPosition(position) => self.position.set(position as u64, true),
            Msg::SetVolume(volume) => self.set_volume(volume),
            Msg::AdjustVolume(delta) => self.set_volume(self.volume.max(0.0) + delta),
            Msg::ToggleMute => {
                if self.volume > 0.0 {
                    self.muted_volume = Some(self.volume);
                    self.set_volume(0.0);
                } else {
                    let volume = self.muted_volume.take().unwrap_or(UNMUTE_FALLBACK_VOLUME);
                    self.set_volume(volume);
                }
            }
            Msg::SetShuffle(shuffle) => {
                self.queue.set_shuffled(shuffle);
                self.queue_changed = true;
                self.send_remote(RemoteOp::Shuffle(shuffle));
            }
            Msg::SetRepeat(mode) => {
                self.repeat = mode;
                self.queue_changed = true;
                self.send_remote(RemoteOp::Repeat(mode));
            }
            Msg::Enqueue(tracks) => {
                self.queue.enqueue(tracks);
                self.queue_changed = true;
            }
            Msg::PlayEntry(key) => {
                if self.queue.play_key(key) {
                    self.jumped();
                }
            }
            Msg::Remove(keys) => self.queue_changed |= self.queue.remove(&keys),
            Msg::ClearQueued => self.queue_changed |= self.queue.clear_queued(),
            Msg::MoveEntry { key, target, after } => {
                self.queue_changed |= self.queue.move_entry(key, target, after, self.repeat, true)
            }
            Msg::SetSkipExplicit(skip) => {
                self.skip_explicit = skip || self.explicit_locked;
                self.skip_current_if_needed();
            }
            Msg::ExplicitFilterLocked(locked) => {
                self.explicit_locked = locked;
                if locked {
                    self.skip_explicit = true;
                }
                self.skip_current_if_needed();
            }
            Msg::SetSeparation(separation) => {
                self.queue.set_separation(separation as usize);
                self.queue_changed = true;
            }
            Msg::PreloadNext => {
                let skip = self.skipper();
                if let Some(track) = self.queue.peek_next(true, self.repeat, skip) {
                    if let Some(uri) = track_uri(&track.rri.id) {
                        self.send(Command::PlayerPreload(uri));
                    }
                }
            }
            Msg::SwitchDevice(device) => self.switch_device(device),
            Msg::PollDevice => {}
            Msg::RemoteState(state, queue) => self.apply_remote(state, queue),
            Msg::DeviceLost => {
                self.device = None;
                self.playing = false;
                self.position.pause();
            }
            Msg::PageLoaded(source, page) => {
                let new_source = self.queue.add_page(source, page);
                self.queue_changed = true;
                if new_source {
                    self.load_current(self.playing);
                }
            }
        }
    }

    fn next_page(&mut self) -> Option<(SongsSource, PageRequest)> {
        let source = self.queue.source().filter(|s| s.is_paginated())?.clone();
        let request = self.queue.next_page_request(self.repeat, true)?;
        let pending = self
            .last_page_request
            .as_ref()
            .is_some_and(|(s, offset, at)| {
                *s == source && *offset == request.offset && at.elapsed() < RETRY_AFTER
            });
        if pending {
            return None;
        }
        self.last_page_request = Some((source.clone(), request.offset, Instant::now()));
        Some((source, request))
    }

    fn snapshot(&self) -> PlaybackSnapshot {
        PlaybackSnapshot {
            current: self.queue.current().map(TrackInfo::from),
            current_key: self.queue.current_key().map(|k| k.to_string()),
            is_playing: self.playing,
            position_ms: self.position.current().min(u32::MAX as u64) as u32,
            shuffle: self.queue.is_shuffled(),
            repeat: self.repeat.into(),
            volume: self.volume,
            source: self.queue.source().map(PlaySource::from),
            has_next: self.queue.has_next(self.repeat),
            device_id: self.device.clone(),
            skip_explicit: self.skip_explicit,
            explicit_filter_locked: self.explicit_locked,
        }
    }

    fn queue_snapshot(&self) -> QueueSnapshot {
        let view = self.queue.view(self.repeat);
        let entry = |(key, track): (EntryKey, Track)| QueueEntry {
            key: key.to_string(),
            track: TrackInfo::from(&track),
        };
        QueueSnapshot {
            current: self
                .queue
                .current_key()
                .zip(self.queue.current().cloned())
                .map(entry),
            queued: view.queued.into_iter().map(entry).collect(),
            context: view.context.into_iter().map(entry).collect(),
            source: self.queue.source().map(PlaySource::from),
        }
    }
}

async fn fetch_page(
    api: &ApiService,
    source: &SongsSource,
    request: PageRequest,
) -> Result<Page<Track>, DomainError> {
    let load = Load::new(LoadPriority::Transport, crate::current_epoch());
    let PageRequest { offset, batch_size } = request;
    match source {
        SongsSource::Playlist(id) => api.get_playlist_tracks(id, offset, batch_size, load).await,
        SongsSource::Album(id) => api.get_album_tracks(id, offset, batch_size, load).await,
        SongsSource::SavedTracks => api.get_saved_tracks(offset, batch_size, load).await,
        _ => Ok(Page::default()),
    }
}

/// Handle owned by the core: send messages and read the latest snapshot.
#[derive(Clone)]
pub struct PlaybackHandle {
    sender: mpsc::UnboundedSender<Msg>,
    snapshot: Arc<Mutex<PlaybackSnapshot>>,
    queue: Arc<Mutex<QueueSnapshot>>,
}

impl PlaybackHandle {
    pub fn send(&self, msg: Msg) {
        let _ = self.sender.send(msg);
    }

    pub fn snapshot(&self) -> PlaybackSnapshot {
        self.snapshot.lock().unwrap().clone()
    }

    pub fn queue(&self) -> QueueSnapshot {
        self.queue.lock().unwrap().clone()
    }
}

pub struct PlaybackInit {
    pub volume: f64,
    pub shuffle: bool,
    pub repeat: RepeatMode,
    pub skip_explicit: bool,
    pub separation: u32,
}

pub fn start(
    runtime: &tokio::runtime::Handle,
    api: Arc<ApiService>,
    player: UnboundedSender<Command>,
    listener: Arc<dyn Listener>,
    init: PlaybackInit,
) -> PlaybackHandle {
    let (sender, mut receiver) = mpsc::unbounded_channel::<Msg>();
    let mut controller = Controller {
        queue: PlayQueue::default(),
        repeat: init.repeat,
        playing: false,
        position: Position {
            known: 0,
            resumed: None,
        },
        volume: init.volume,
        muted_volume: None,
        skip_explicit: init.skip_explicit,
        explicit_locked: false,
        last_page_request: None,
        commands: vec![],
        device: None,
        last_remote_queue: None,
        queue_changed: false,
    };
    controller.queue.set_shuffled(init.shuffle);
    controller.queue.set_separation(init.separation as usize);
    let snapshot = Arc::new(Mutex::new(controller.snapshot()));
    let queue = Arc::new(Mutex::new(QueueSnapshot::default()));
    let handle = PlaybackHandle {
        sender: sender.clone(),
        snapshot: Arc::clone(&snapshot),
        queue: Arc::clone(&queue),
    };

    // Connect devices are polled, as on Linux.
    let poller = sender.clone();
    runtime.spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(5));
        loop {
            interval.tick().await;
            if poller.send(Msg::PollDevice).is_err() {
                break;
            }
        }
    });

    runtime.spawn(async move {
        while let Some(msg) = receiver.recv().await {
            let syncs_only = matches!(msg, Msg::SyncPosition(_) | Msg::PollDevice);
            let polling = matches!(msg, Msg::PollDevice);
            controller.handle(msg);
            let mut remote = vec![];
            for out in controller.commands.drain(..) {
                match out {
                    Out::Local(command) => {
                        let _ = player.unbounded_send(command);
                    }
                    Out::Remote(device, op) => remote.push((device, op)),
                }
            }
            if !remote.is_empty() {
                tokio::spawn(connect::run(
                    Arc::clone(&api),
                    remote,
                    sender.clone(),
                    Arc::clone(&listener),
                ));
            }
            if polling && controller.device.is_some() {
                let last = controller.last_remote_queue.clone();
                let api = Arc::clone(&api);
                let sender = sender.clone();
                tokio::spawn(async move { connect::poll(api, last, sender).await });
            }
            if let Some((source, request)) = controller.next_page() {
                let api = Arc::clone(&api);
                let sender = sender.clone();
                tokio::spawn(async move {
                    match fetch_page(&api, &source, request).await {
                        Ok(page) => {
                            let _ = sender.send(Msg::PageLoaded(source, page));
                        }
                        Err(e) => warn!("queue page for {source:?} failed: {e}"),
                    }
                });
            }
            if std::mem::take(&mut controller.queue_changed) {
                let q = controller.queue_snapshot();
                *queue.lock().unwrap() = q.clone();
                listener.on_event(CoreEvent::QueueChanged { queue: q });
            }
            let current = controller.snapshot();
            let changed = {
                let mut last = snapshot.lock().unwrap();
                let changed = !syncs_only || *last != current;
                *last = current.clone();
                changed
            };
            if changed {
                listener.on_event(CoreEvent::PlaybackChanged { playback: current });
            }
        }
    });
    handle
}

/// Batch size used when the core preloads a context for Swift.
pub const PAGE_SIZE: usize = CONTEXT_PAGE_SIZE;

#[cfg(test)]
mod tests {
    use super::*;
    use riff_api::models::{ImageSet, ResourceId};

    fn track(id: &str) -> Track {
        Track {
            rri: ResourceId {
                provider: Default::default(),
                id: id.to_string(),
                uri: None,
            },
            title: format!("Track {id}"),
            artists: vec![],
            album: None,
            duration_ms: 1000,
            track_number: None,
            disc_number: None,
            content_rating: ContentRating::None,
            isrc: None,
            art: ImageSet::default(),
            playable: true,
            popularity: None,
            saved: None,
            preview_url: None,
            url: None,
        }
    }

    fn controller() -> Controller {
        Controller {
            queue: PlayQueue::default(),
            repeat: RepeatMode::Off,
            playing: false,
            position: Position {
                known: 0,
                resumed: None,
            },
            volume: 0.5,
            muted_volume: None,
            skip_explicit: false,
            explicit_locked: false,
            last_page_request: None,
            commands: vec![],
            device: None,
            last_remote_queue: None,
            queue_changed: false,
        }
    }

    fn play(c: &mut Controller, ids: &[&str], start: Option<&str>) {
        c.handle(Msg::PlayContext {
            source: None,
            tracks: ids.iter().map(|id| track(id)).collect(),
            total: None,
            start: start.map(str::to_string),
            shuffle: Some(false),
        });
    }

    #[test]
    fn play_context_starts_at_requested_track() {
        // Real base62 ids, so the player gets a load command.
        const B: &str = "4uLU6hMCjMI75M1A2tKUQC";
        let mut c = controller();
        play(&mut c, &["7GhIk7Il098yCjg4BQjzvb", B], Some(B));
        assert_eq!(c.current_id().as_deref(), Some(B));
        assert!(c.playing);
        assert!(matches!(
            c.commands.last(),
            Some(Out::Local(Command::PlayerLoad { resume: true, .. }))
        ));
    }

    #[test]
    fn next_and_end_of_context_stop() {
        let mut c = controller();
        play(&mut c, &["a", "b"], None);
        assert_eq!(c.current_id().as_deref(), Some("a"));
        c.handle(Msg::Next);
        assert_eq!(c.current_id().as_deref(), Some("b"));
        c.handle(Msg::TrackEnded);
        assert_eq!(c.current_id(), None);
        assert!(!c.playing);
    }

    #[test]
    fn repeat_context_wraps() {
        let mut c = controller();
        play(&mut c, &["a", "b"], Some("b"));
        c.handle(Msg::SetRepeat(RepeatMode::Context));
        c.handle(Msg::TrackEnded);
        assert_eq!(c.current_id().as_deref(), Some("a"));
    }

    #[test]
    fn explicit_tracks_are_skipped_when_asked() {
        let mut c = controller();
        let mut explicit = track("x");
        explicit.content_rating = ContentRating::Explicit;
        c.handle(Msg::SetSkipExplicit(true));
        c.handle(Msg::PlayContext {
            source: None,
            tracks: vec![explicit, track("y")],
            total: None,
            start: Some("x".into()),
            shuffle: Some(false),
        });
        assert_eq!(c.current_id().as_deref(), Some("y"));
    }

    #[test]
    fn connect_commands_go_to_the_device() {
        const A: &str = "7GhIk7Il098yCjg4BQjzvb";
        let mut c = controller();
        play(&mut c, &[A], Some(A));
        c.commands.clear();
        c.handle(Msg::SwitchDevice(Some("dev".into())));
        assert!(matches!(c.commands[0], Out::Local(Command::PlayerStop)));
        assert!(c.commands.iter().any(|o| matches!(
            o,
            Out::Remote(d, RemoteOp::PlayUris { .. }) if d == "dev"
        )));
        c.commands.clear();
        c.handle(Msg::Pause);
        assert!(matches!(&c.commands[..], [Out::Remote(_, RemoteOp::Pause)]));
        c.commands.clear();
        c.handle(Msg::SwitchDevice(None));
        assert!(matches!(c.commands[0], Out::Remote(_, RemoteOp::Pause)));
        assert!(c
            .commands
            .iter()
            .any(|o| matches!(o, Out::Local(Command::PlayerLoad { resume: false, .. }))));
    }

    #[test]
    fn toggle_mute_restores_volume() {
        let mut c = controller();
        c.handle(Msg::ToggleMute);
        assert_eq!(c.volume, 0.0);
        c.handle(Msg::ToggleMute);
        assert_eq!(c.volume, 0.5);
    }

    #[test]
    fn queued_tracks_play_before_context() {
        let mut c = controller();
        play(&mut c, &["a", "b"], None);
        c.handle(Msg::Enqueue(vec![track("q")]));
        c.handle(Msg::Next);
        assert_eq!(c.current_id().as_deref(), Some("q"));
        let snapshot = c.queue_snapshot();
        assert!(snapshot.queued.is_empty());
    }
}
