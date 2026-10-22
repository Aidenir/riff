//! Flat, FFI-friendly copies of the riff-api models.
//!
//! Swift only ever sees these records. Ids stay strings so the Swift side can
//! hand them back to the core unchanged.

use riff_api::models::{
    Album, AlbumType, Artist, ContentRating, ImageSet, LibraryNode, Lyrics, Playlist, Track, User,
};
use riff_core::play_queue::SongsSource;

const SMALL_ART: u32 = 64;
const MEDIUM_ART: u32 = 300;

#[derive(uniffi::Record, Clone, Debug, Default, PartialEq)]
pub struct Artwork {
    pub small: Option<String>,
    pub medium: Option<String>,
    pub large: Option<String>,
}

impl From<&ImageSet> for Artwork {
    fn from(art: &ImageSet) -> Self {
        if art.is_resource() {
            return Self::default();
        }
        Self {
            small: art.best_for_width(SMALL_ART).map(str::to_owned),
            medium: art.best_for_width(MEDIUM_ART).map(str::to_owned),
            large: art.largest().map(str::to_owned),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct NamedLink {
    pub id: String,
    pub name: String,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct TrackInfo {
    pub id: String,
    pub uri: String,
    pub title: String,
    pub artists: Vec<NamedLink>,
    pub album: Option<NamedLink>,
    pub duration_ms: u32,
    pub track_number: Option<u32>,
    pub disc_number: Option<u32>,
    pub explicit: bool,
    pub playable: bool,
    pub saved: Option<bool>,
    pub art: Artwork,
    pub url: Option<String>,
}

impl From<&Track> for TrackInfo {
    fn from(t: &Track) -> Self {
        Self {
            id: t.rri.id.clone(),
            uri: t
                .rri
                .uri
                .clone()
                .unwrap_or_else(|| format!("spotify:track:{}", t.rri.id)),
            title: t.title.clone(),
            artists: t
                .artists
                .iter()
                .map(|a| NamedLink {
                    id: a.rri.id.clone(),
                    name: a.name.clone(),
                })
                .collect(),
            album: t.album.as_ref().map(|a| NamedLink {
                id: a.rri.id.clone(),
                name: a.name.clone(),
            }),
            duration_ms: t.duration_ms,
            track_number: t.track_number,
            disc_number: t.disc_number,
            explicit: matches!(t.content_rating, ContentRating::Explicit),
            playable: t.playable,
            saved: t.saved,
            art: Artwork::from(&t.art),
            url: t.url.clone(),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct AlbumInfo {
    pub id: String,
    pub title: String,
    pub artists: Vec<NamedLink>,
    pub art: Artwork,
    pub album_type: String,
    pub release_date: Option<String>,
    pub year: Option<i32>,
    pub total_tracks: Option<u32>,
    pub label: Option<String>,
    pub copyright: Option<String>,
    pub genres: Vec<String>,
    pub popularity: Option<u32>,
    pub saved: Option<bool>,
    pub url: Option<String>,
}

fn album_type_name(kind: &AlbumType) -> String {
    match kind {
        AlbumType::Album => "Album".into(),
        AlbumType::Single => "Single".into(),
        AlbumType::Ep => "EP".into(),
        AlbumType::Compilation => "Compilation".into(),
        AlbumType::Live => "Live".into(),
        AlbumType::Soundtrack => "Soundtrack".into(),
        AlbumType::Other(other) => other.clone(),
    }
}

impl From<&Album> for AlbumInfo {
    fn from(a: &Album) -> Self {
        Self {
            id: a.rri.id.clone(),
            title: a.title.clone(),
            artists: a
                .artists
                .iter()
                .map(|r| NamedLink {
                    id: r.rri.id.clone(),
                    name: r.name.clone(),
                })
                .collect(),
            art: Artwork::from(&a.art),
            album_type: album_type_name(&a.album_type),
            release_date: a.release_date.map(|d| match (d.month, d.day) {
                (Some(m), Some(day)) => format!("{:04}-{:02}-{:02}", d.year, m, day),
                (Some(m), None) => format!("{:04}-{:02}", d.year, m),
                _ => format!("{:04}", d.year),
            }),
            year: a.release_date.map(|d| d.year),
            total_tracks: a.total_tracks,
            label: a.label.clone(),
            copyright: a.copyright.clone(),
            genres: a.genres.clone(),
            popularity: a.popularity,
            saved: a.saved,
            url: a.url.clone(),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct ArtistInfo {
    pub id: String,
    pub name: String,
    pub art: Artwork,
    pub genres: Vec<String>,
    pub popularity: Option<u32>,
    pub follower_count: Option<u64>,
    pub bio: Option<String>,
    pub following: Option<bool>,
    pub url: Option<String>,
}

impl From<&Artist> for ArtistInfo {
    fn from(a: &Artist) -> Self {
        Self {
            id: a.rri.id.clone(),
            name: a.name.clone(),
            art: Artwork::from(&a.art),
            genres: a.genres.clone(),
            popularity: a.popularity,
            follower_count: a.follower_count,
            bio: a.bio.clone(),
            following: a.following,
            url: a.url.clone(),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct PlaylistInfo {
    pub id: String,
    pub title: String,
    pub description: Option<String>,
    pub owner: Option<NamedLink>,
    pub art: Artwork,
    pub total_tracks: Option<u32>,
    pub collaborative: Option<bool>,
    pub public: Option<bool>,
    pub following: Option<bool>,
    pub url: Option<String>,
}

impl From<&Playlist> for PlaylistInfo {
    fn from(p: &Playlist) -> Self {
        Self {
            id: p.rri.id.clone(),
            title: p.title.clone(),
            description: p.description.clone().filter(|d| !d.is_empty()),
            owner: p.owner.as_ref().map(|o| NamedLink {
                id: o.rri.id.clone(),
                name: o.display_name.clone(),
            }),
            art: Artwork::from(&p.art),
            total_tracks: p.total_tracks,
            collaborative: p.collaborative,
            public: p.public,
            following: p.following,
            url: p.url.clone(),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct UserInfo {
    pub id: String,
    pub display_name: String,
    pub art: Artwork,
    pub follower_count: Option<u64>,
    pub product: Option<String>,
    pub country: Option<String>,
    pub url: Option<String>,
}

impl From<&User> for UserInfo {
    fn from(u: &User) -> Self {
        Self {
            id: u.rri.id.clone(),
            display_name: u.display_name.clone(),
            art: Artwork::from(&u.art),
            follower_count: u.follower_count,
            product: u.product.clone(),
            country: u.country.clone(),
            url: u.url.clone(),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct TrackPage {
    pub items: Vec<TrackInfo>,
    pub offset: u32,
    pub total: Option<u32>,
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct AlbumPage {
    pub items: Vec<AlbumInfo>,
    pub offset: u32,
    pub total: Option<u32>,
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct PlaylistPage {
    pub items: Vec<PlaylistInfo>,
    pub offset: u32,
    pub total: Option<u32>,
}

#[derive(uniffi::Record, Clone, Debug)]
pub struct ArtistPage {
    pub items: Vec<ArtistInfo>,
    pub next_cursor: Option<String>,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct SearchResult {
    pub tracks: Vec<TrackInfo>,
    pub albums: Vec<AlbumInfo>,
    pub artists: Vec<ArtistInfo>,
    pub playlists: Vec<PlaylistInfo>,
    pub total_tracks: Option<u32>,
    pub total_albums: Option<u32>,
    pub total_artists: Option<u32>,
    pub total_playlists: Option<u32>,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SearchScope {
    Tracks,
    Albums,
    Artists,
    Playlists,
}

/// One row of the playlist library tree, flattened depth first.
#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct LibraryEntry {
    pub id: String,
    pub name: Option<String>,
    pub is_folder: bool,
    pub depth: u32,
}

pub fn flatten_library(nodes: &[LibraryNode], depth: u32, out: &mut Vec<LibraryEntry>) {
    for node in nodes {
        match node {
            LibraryNode::Folder { id, name, children } => {
                out.push(LibraryEntry {
                    id: id.clone(),
                    name: Some(name.clone()),
                    is_folder: true,
                    depth,
                });
                flatten_library(children, depth + 1, out);
            }
            LibraryNode::Playlist { id, name } => out.push(LibraryEntry {
                id: id.clone(),
                name: name.clone(),
                is_folder: false,
                depth,
            }),
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct LyricsLine {
    pub start_ms: u32,
    pub words: String,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct LyricsInfo {
    pub synced: bool,
    pub lines: Vec<LyricsLine>,
    pub provider: Option<String>,
}

impl From<&Lyrics> for LyricsInfo {
    fn from(l: &Lyrics) -> Self {
        Self {
            synced: l.synced,
            lines: l
                .lines
                .iter()
                .map(|line| LyricsLine {
                    start_ms: line.start_ms,
                    words: line.words.clone(),
                })
                .collect(),
            provider: l.provider.clone(),
        }
    }
}

/// Where a list of tracks being played comes from.
#[derive(uniffi::Enum, Clone, Debug, PartialEq, Eq)]
pub enum PlaySource {
    Playlist { id: String },
    Album { id: String },
    Artist { id: String },
    SavedTracks,
    Search { query: String },
    Radio { track_id: String },
}

impl From<PlaySource> for SongsSource {
    fn from(s: PlaySource) -> Self {
        match s {
            PlaySource::Playlist { id } => SongsSource::Playlist(id),
            PlaySource::Album { id } => SongsSource::Album(id),
            PlaySource::Artist { id } => SongsSource::Artist(id),
            PlaySource::SavedTracks => SongsSource::SavedTracks,
            PlaySource::Search { query } => SongsSource::Search(query),
            PlaySource::Radio { track_id } => SongsSource::Radio(track_id),
        }
    }
}

impl From<&SongsSource> for PlaySource {
    fn from(s: &SongsSource) -> Self {
        match s {
            SongsSource::Playlist(id) => PlaySource::Playlist { id: id.clone() },
            SongsSource::Album(id) => PlaySource::Album { id: id.clone() },
            SongsSource::Artist(id) => PlaySource::Artist { id: id.clone() },
            SongsSource::SavedTracks => PlaySource::SavedTracks,
            SongsSource::Search(query) => PlaySource::Search {
                query: query.clone(),
            },
            SongsSource::Radio(id) => PlaySource::Radio {
                track_id: id.clone(),
            },
        }
    }
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum Repeat {
    Off,
    Track,
    Context,
}

impl From<riff_api::RepeatMode> for Repeat {
    fn from(r: riff_api::RepeatMode) -> Self {
        match r {
            riff_api::RepeatMode::Off => Repeat::Off,
            riff_api::RepeatMode::Track => Repeat::Track,
            riff_api::RepeatMode::Context => Repeat::Context,
        }
    }
}

impl From<Repeat> for riff_api::RepeatMode {
    fn from(r: Repeat) -> Self {
        match r {
            Repeat::Off => riff_api::RepeatMode::Off,
            Repeat::Track => riff_api::RepeatMode::Track,
            Repeat::Context => riff_api::RepeatMode::Context,
        }
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct PlaybackSnapshot {
    pub current: Option<TrackInfo>,
    /// Queue key of the current entry, matching [`QueueEntry::key`].
    pub current_key: Option<String>,
    pub is_playing: bool,
    pub position_ms: u32,
    pub shuffle: bool,
    pub repeat: Repeat,
    pub volume: f64,
    pub source: Option<PlaySource>,
    pub has_next: bool,
    /// Spotify Connect device playing, `None` when playing on this Mac.
    pub device_id: Option<String>,
    pub skip_explicit: bool,
    pub explicit_filter_locked: bool,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct QueueEntry {
    pub key: String,
    pub track: TrackInfo,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq, Default)]
pub struct QueueSnapshot {
    pub current: Option<QueueEntry>,
    pub queued: Vec<QueueEntry>,
    pub context: Vec<QueueEntry>,
    pub source: Option<PlaySource>,
}

#[derive(uniffi::Enum, Clone, Debug, PartialEq)]
pub enum CoreEvent {
    /// The browser must be opened on this URL to finish logging in.
    OpenLoginUrl {
        url: String,
    },
    LoggedIn {
        username: String,
    },
    LoggedOut,
    LoginFailed,
    NotPremium,
    /// Spotify refuses to stream to this account (PlayPlay DRM).
    DrmBlocked,
    ConnectionLost {
        lost: bool,
    },
    Notification {
        message: String,
    },
    PlaybackChanged {
        playback: PlaybackSnapshot,
    },
    QueueChanged {
        queue: QueueSnapshot,
    },
    /// Something in the library changed: refresh saved lists.
    LibraryChanged,
}

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum CoreError {
    #[error("{message}")]
    Failed { message: String },
    #[error("not logged in")]
    NotLoggedIn,
}

impl From<riff_api::DomainError> for CoreError {
    fn from(e: riff_api::DomainError) -> Self {
        CoreError::Failed {
            message: e.to_string(),
        }
    }
}

/// Something the user listened to lately, for the home page.
#[derive(uniffi::Enum, Clone, Debug, PartialEq)]
pub enum RecentItem {
    Album { album: AlbumInfo },
    Playlist { playlist: PlaylistInfo },
    Artist { artist: ArtistInfo },
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeviceType {
    Phone,
    Computer,
    Speaker,
    Tv,
    Other,
}

/// A Spotify Connect device the user can play on.
#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    pub kind: DeviceType,
    pub is_active: bool,
    pub volume_percent: Option<u8>,
}

impl From<&riff_api::Device> for DeviceInfo {
    fn from(d: &riff_api::Device) -> Self {
        Self {
            id: d.id.clone(),
            name: d.label.clone(),
            kind: match d.kind {
                riff_api::DeviceKind::Phone => DeviceType::Phone,
                riff_api::DeviceKind::Computer => DeviceType::Computer,
                riff_api::DeviceKind::Speaker => DeviceType::Speaker,
                riff_api::DeviceKind::Tv => DeviceType::Tv,
                riff_api::DeviceKind::Other => DeviceType::Other,
            },
            is_active: d.is_active,
            volume_percent: d.volume_percent,
        }
    }
}
