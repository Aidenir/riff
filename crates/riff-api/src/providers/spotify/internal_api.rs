//! Spotify APIs that are not part of the public Web API.
//!
//! These are the endpoints Spotify's own clients call. They are undocumented,
//! can change without notice, and only accept a token minted for Spotify's own
//! client id (`riff_auth::SESSION_CLIENT_ID`): a Web API token is refused with
//! a 403. Only what the Web API cannot do belongs here.

use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use prost::Message;
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use reqwest::Method;
use spotify_api::apis;

use crate::error::DomainError;
use crate::models::{LibraryNode, Lyrics, LyricsLine};
use crate::token::TokenProvider;

/// Lists the hosts serving the internal API closest to the user.
const RESOLVE_URL: &str = "https://apresolve.spotify.com/?type=spclient";

/// Used when no host could be resolved.
const FALLBACK_HOST: &str = "spclient.wg.spotify.com";

const PROTOBUF: &str = "application/x-protobuf";

const CLIENT_TOKEN_URL: &str = "https://clienttoken.spotify.com/v1/clienttoken";
/// A version of the official desktop client
const CLIENT_VERSION: &str = "1.2.70.61.g856ccd63";

/// A random id for this run, as a client token names a device.
fn device_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let state = std::collections::hash_map::RandomState::new();
    let mut id = String::new();
    for part in 0..2 {
        let mut hasher = state.build_hasher();
        hasher.write_u32(part);
        hasher.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or_default(),
        );
        id.push_str(&format!("{:016x}", hasher.finish()));
    }
    id
}
const JSON: &str = "application/json";

/// Entries of the library asked for at once.
const ROOTLIST_PAGE: usize = 500;

fn is_public(level: proto::PermissionLevel) -> Option<bool> {
    match level {
        proto::PermissionLevel::Blocked => Some(false),
        proto::PermissionLevel::Viewer | proto::PermissionLevel::Contributor => Some(true),
        proto::PermissionLevel::Unknown => None,
    }
}

fn level_for(public: bool) -> proto::PermissionLevel {
    if public {
        proto::PermissionLevel::Viewer
    } else {
        proto::PermissionLevel::Blocked
    }
}

fn public_from(permission: Option<proto::Permission>) -> Result<bool, DomainError> {
    permission
        .and_then(|permission| permission.permission_level)
        .and_then(|level| proto::PermissionLevel::try_from(level).ok())
        .and_then(is_public)
        .ok_or_else(|| {
            error!("spotify: internal API answered without a permission level");
            DomainError::Parse("no permission level".to_string())
        })
}

pub struct SpotifyInternalApi {
    token_provider: Arc<dyn TokenProvider>,
    host: RwLock<Option<String>>,
    // Identifies this client to the internal API, which refuses some
    // requests without it
    client_token: tokio::sync::Mutex<Option<(String, Instant)>>,
}

impl SpotifyInternalApi {
    pub fn new(token_provider: Arc<dyn TokenProvider>) -> Self {
        Self {
            token_provider,
            host: RwLock::new(None),
            client_token: tokio::sync::Mutex::new(None),
        }
    }

    /// A client token, asked for as Spotify's desktop client does.
    async fn client_token(&self) -> Option<String> {
        let mut cached = self.client_token.lock().await;
        if let Some((token, until)) = cached.as_ref() {
            if Instant::now() < *until {
                return Some(token.clone());
            }
        }
        let request = proto::ClientTokenRequest {
            request_type: Some(1),
            client_data: Some(proto::ClientDataRequest {
                client_version: Some(CLIENT_VERSION.to_string()),
                client_id: Some(riff_auth::SESSION_CLIENT_ID.to_string()),
                connectivity_sdk_data: Some(proto::ConnectivitySdkData {
                    platform_specific_data: Some(proto::PlatformSpecificData {
                        desktop_linux: Some(proto::NativeDesktopLinuxData {
                            system_name: Some("Linux".to_string()),
                            system_release: Some(String::new()),
                            system_version: Some(String::new()),
                            hardware: Some(std::env::consts::ARCH.to_string()),
                        }),
                    }),
                    device_id: Some(device_id()),
                }),
            }),
        };
        let response = crate::http::api_client()
            .post(CLIENT_TOKEN_URL)
            .header(CONTENT_TYPE, PROTOBUF)
            .header(ACCEPT, PROTOBUF)
            .body(request.encode_to_vec());
        let body = super::request::send(response)
            .await
            .inspect_err(|e| warn!("spotify: no client token: {e}"))
            .ok()?;
        let granted = decode::<proto::ClientTokenResponse>(&body)
            .ok()?
            .granted_token?;
        let token = granted.token?;
        // Renewed a little early
        let lifetime = granted.refresh_after_seconds.unwrap_or(3600).max(60) as u64;
        let until = Instant::now() + Duration::from_secs(lifetime.saturating_sub(30));
        *cached = Some((token.clone(), until));
        Some(token)
    }

    pub async fn set_playlist_public(&self, id: &str, public: bool) -> Result<bool, DomainError> {
        let request = proto::SetPermissionLevelRequest {
            permission_level: Some(level_for(public).into()),
        };
        let path = format!("{}/level", permission_path(id));
        let body = self
            .request(Method::POST, &path, request.encode_to_vec())
            .await?;
        let response: proto::SetPermissionResponse = decode(&body)?;
        public_from(response.resulting_permission)
    }

    async fn request(
        &self,
        method: Method,
        path: &str,
        body: Vec<u8>,
    ) -> Result<Vec<u8>, DomainError> {
        self.request_as(method, path, body, PROTOBUF).await
    }

    async fn request_as(
        &self,
        method: Method,
        path: &str,
        body: Vec<u8>,
        content_type: &str,
    ) -> Result<Vec<u8>, DomainError> {
        #[cfg(debug_assertions)]
        if let Some(err) = crate::dev::simulated_failure() {
            return Err(err);
        }

        let token = self
            .token_provider
            .access_token()
            .ok_or(DomainError::NoToken)?;
        let url = format!("https://{}{path}", self.host().await);
        let client_token = self.client_token().await;
        let mut request = crate::http::api_client()
            .request(method, url)
            .bearer_auth(token)
            .header(CONTENT_TYPE, content_type)
            .header(ACCEPT, content_type)
            // Some endpoints only answer the web player
            .header("App-Platform", "WebPlayer")
            .body(body);
        if let Some(client_token) = client_token {
            request = request.header("client-token", client_token);
        }
        super::request::send(request).await
    }

    /// The user's playlists and the folders they are sorted into, in the
    /// order the official clients list them. The Web API has no folders.
    pub async fn get_library_tree(&self, username: &str) -> Result<Vec<LibraryNode>, DomainError> {
        let mut entries = Vec::new();
        loop {
            let path = format!(
                "/playlist/v2/user/{}/rootlist?decorate=revision,attributes,length&from={}&length={}",
                apis::urlencode(username),
                entries.len(),
                ROOTLIST_PAGE
            );
            let body = self.request(Method::GET, &path, Vec::new()).await?;
            let list: proto::SelectedListContent = decode(&body)?;
            let total = list.length.unwrap_or(0).max(0) as usize;
            let Some(contents) = list.contents else {
                break;
            };
            let names_aligned = contents.meta_items.len() == contents.items.len();
            let fetched = contents.items.len();
            for (i, item) in contents.items.into_iter().enumerate() {
                let name = names_aligned
                    .then(|| contents.meta_items[i].attributes.as_ref())
                    .flatten()
                    .and_then(|a| a.name.clone());
                entries.push((item.uri.unwrap_or_default(), name));
            }
            if fetched < ROOTLIST_PAGE || entries.len() >= total {
                break;
            }
        }
        Ok(library_tree(entries))
    }

    pub async fn get_lyrics(&self, track_id: &str) -> Result<Lyrics, DomainError> {
        let path = format!(
            "/color-lyrics/v2/track/{}?format=json&vocalRemoval=false&market=from_token",
            apis::urlencode(track_id)
        );
        let body = self
            .request_as(Method::GET, &path, Vec::new(), JSON)
            .await?;
        parse_lyrics(&body)
    }

    /// Ids of the tracks of the radio station started from track `track_id`.
    pub async fn get_track_radio(&self, track_id: &str) -> Result<Vec<String>, DomainError> {
        let seed = format!("spotify:track:{track_id}");
        let path = format!(
            "/radio-apollo/v3/stations/{}?autoplay=false&count=50",
            apis::urlencode(&seed)
        );
        let station = self.request_as(Method::GET, &path, Vec::new(), JSON).await;
        if let Ok(ids) = station.and_then(|body| track_ids_of_station(&body)) {
            if !ids.is_empty() {
                return Ok(ids);
            }
        }
        // Else the radio playlist made from the track
        let path = format!(
            "/inspiredby-mix/v2/seed_to_playlist/{}?response-format=json",
            apis::urlencode(&seed)
        );
        let body = self.request_as(Method::GET, &path, Vec::new(), JSON).await?;
        let mix: serde_json::Value = serde_json::from_slice(&body)
            .map_err(|e| DomainError::Parse(format!("song radio: {e}")))?;
        let playlist = mix
            .get("mediaItems")
            .and_then(|items| items.get(0))
            .and_then(|item| item.get("uri"))
            .and_then(|uri| uri.as_str())
            .and_then(|uri| uri.rsplit_once(':'))
            .map(|(_, id)| id.to_string())
            .ok_or_else(|| DomainError::Parse("song radio: no playlist".to_string()))?;
        let path = format!(
            "/playlist/v2/playlist/{}?from=0&length=100",
            apis::urlencode(&playlist)
        );
        let body = self.request(Method::GET, &path, Vec::new()).await?;
        let list: proto::SelectedListContent = decode(&body)?;
        Ok(list
            .contents
            .map(|c| c.items)
            .unwrap_or_default()
            .into_iter()
            .filter_map(|item| Some(item.uri?.strip_prefix("spotify:track:")?.to_string()))
            .collect())
    }

    /// Ids of tracks that would fit in a playlist, as the official clients
    /// suggest them under it.
    pub async fn get_playlist_extension(
        &self,
        playlist_id: &str,
        skip: &[String],
        count: usize,
    ) -> Result<Vec<String>, DomainError> {
        let request = serde_json::json!({
            "playlistURI": format!("spotify:playlist:{playlist_id}"),
            "trackSkipIDs": skip,
            "numResults": count,
        });
        let body = self
            .request_as(
                Method::POST,
                "/playlistextender/extendp/",
                request.to_string().into_bytes(),
                JSON,
            )
            .await?;
        let response: serde_json::Value = serde_json::from_slice(&body)
            .map_err(|e| DomainError::Parse(format!("playlist extension: {e}")))?;
        Ok(response
            .get("recommendedTracks")
            .and_then(|tracks| tracks.as_array())
            .into_iter()
            .flatten()
            .filter_map(|track| {
                let id = track.get("id").or_else(|| track.get("originalId"))?;
                Some(id.as_str()?.to_string())
            })
            .collect())
    }

    async fn host(&self) -> String {
        if let Some(host) = self.host.read().unwrap().clone() {
            return host;
        }
        match resolve_host().await {
            Some(host) => {
                *self.host.write().unwrap() = Some(host.clone());
                host
            }
            None => FALLBACK_HOST.to_string(),
        }
    }
}

fn permission_path(id: &str) -> String {
    format!(
        "/playlist-permission/v1/playlist/{}/permission/base",
        apis::urlencode(id)
    )
}

async fn resolve_host() -> Option<String> {
    let response = crate::http::api_client()
        .get(RESOLVE_URL)
        .send()
        .await
        .inspect_err(|e| warn!("spotify: could not resolve an internal API host: {e}"))
        .ok()?;
    let listed: serde_json::Value = serde_json::from_slice(&response.bytes().await.ok()?).ok()?;
    let host = listed.get("spclient")?.get(0)?.as_str()?;
    Some(host.strip_suffix(":443").unwrap_or(host).to_string())
}

/// Builds the folder tree from the flat library listing, where a folder is
/// opened by `spotify:start-group:<id>:<name>` and closed by
/// `spotify:end-group:<id>`.
fn library_tree(entries: Vec<(String, Option<String>)>) -> Vec<LibraryNode> {
    // The folders being filled, outermost first, below the root
    let mut open: Vec<(String, String, Vec<LibraryNode>)> = Vec::new();
    let mut root = Vec::new();
    for (uri, name) in entries {
        let node = if let Some(group) = uri.strip_prefix("spotify:start-group:") {
            let (id, name) = group.split_once(':').unwrap_or((group, ""));
            open.push((id.to_string(), decode_group_name(name), Vec::new()));
            continue;
        } else if uri.starts_with("spotify:end-group:") {
            let Some((id, name, children)) = open.pop() else {
                continue;
            };
            LibraryNode::Folder { id, name, children }
        } else if let Some((_, id)) = uri.rsplit_once(":playlist:") {
            LibraryNode::Playlist {
                id: id.to_string(),
                name: name.filter(|n| !n.is_empty()),
            }
        } else {
            continue;
        };
        match open.last_mut() {
            Some((_, _, children)) => children.push(node),
            None => root.push(node),
        }
    }
    // Close folders a truncated listing left open
    while let Some((id, name, children)) = open.pop() {
        let node = LibraryNode::Folder { id, name, children };
        match open.last_mut() {
            Some((_, _, siblings)) => siblings.push(node),
            None => root.push(node),
        }
    }
    root
}

/// Folder names are form-encoded: `+` for spaces, `%XX` for the rest.
fn decode_group_name(name: &str) -> String {
    let bytes = name.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < bytes.len() => {
                match std::str::from_utf8(&bytes[i + 1..i + 3])
                    .ok()
                    .and_then(|hex| u8::from_str_radix(hex, 16).ok())
                {
                    Some(byte) => {
                        out.push(byte);
                        i += 2;
                    }
                    None => out.push(b'%'),
                }
            }
            byte => out.push(byte),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn track_ids_of_station(body: &[u8]) -> Result<Vec<String>, DomainError> {
    let station: serde_json::Value = serde_json::from_slice(body)
        .map_err(|e| DomainError::Parse(format!("radio station: {e}")))?;
    Ok(station
        .get("tracks")
        .and_then(|tracks| tracks.as_array())
        .into_iter()
        .flatten()
        .filter_map(|track| {
            let uri = track.get("uri")?.as_str()?;
            Some(uri.strip_prefix("spotify:track:")?.to_string())
        })
        .collect())
}

fn parse_lyrics(body: &[u8]) -> Result<Lyrics, DomainError> {
    let response: serde_json::Value =
        serde_json::from_slice(body).map_err(|e| DomainError::Parse(format!("lyrics: {e}")))?;
    let lyrics = response
        .get("lyrics")
        .ok_or_else(|| DomainError::Parse("lyrics: no lyrics".to_string()))?;
    let synced = lyrics.get("syncType").and_then(|t| t.as_str()) == Some("LINE_SYNCED");
    // Times are strings of milliseconds
    let millis = |value: Option<&serde_json::Value>| -> u32 {
        match value {
            Some(serde_json::Value::String(s)) => s.parse().unwrap_or(0),
            Some(serde_json::Value::Number(n)) => n.as_u64().unwrap_or(0) as u32,
            _ => 0,
        }
    };
    let lines = lyrics
        .get("lines")
        .and_then(|lines| lines.as_array())
        .into_iter()
        .flatten()
        .map(|line| LyricsLine {
            start_ms: millis(line.get("startTimeMs")),
            words: line
                .get("words")
                .and_then(|w| w.as_str())
                .unwrap_or_default()
                .to_string(),
        })
        .collect();
    Ok(Lyrics {
        synced,
        lines,
        provider: lyrics
            .get("providerDisplayName")
            .or_else(|| lyrics.get("provider"))
            .and_then(|p| p.as_str())
            .map(str::to_string),
    })
}

fn decode<M: Message + Default>(body: &[u8]) -> Result<M, DomainError> {
    M::decode(body).map_err(|e| {
        error!("spotify: internal API response did not match the expected message: {e}");
        DomainError::Parse(e.to_string())
    })
}

mod proto {
    #[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
    #[repr(i32)]
    pub enum PermissionLevel {
        Unknown = 0,
        Blocked = 1,
        Viewer = 2,
        Contributor = 3,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct Permission {
        #[prost(enumeration = "PermissionLevel", optional, tag = "2")]
        pub permission_level: Option<i32>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct SetPermissionLevelRequest {
        #[prost(enumeration = "PermissionLevel", optional, tag = "1")]
        pub permission_level: Option<i32>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct SetPermissionResponse {
        #[prost(message, optional, tag = "1")]
        pub resulting_permission: Option<Permission>,
    }

    // From clienttoken_http.proto, with only the fields used here

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct ClientTokenRequest {
        #[prost(int32, optional, tag = "1")]
        pub request_type: Option<i32>,
        #[prost(message, optional, tag = "2")]
        pub client_data: Option<ClientDataRequest>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct ClientDataRequest {
        #[prost(string, optional, tag = "1")]
        pub client_version: Option<String>,
        #[prost(string, optional, tag = "2")]
        pub client_id: Option<String>,
        #[prost(message, optional, tag = "3")]
        pub connectivity_sdk_data: Option<ConnectivitySdkData>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct ConnectivitySdkData {
        #[prost(message, optional, tag = "1")]
        pub platform_specific_data: Option<PlatformSpecificData>,
        #[prost(string, optional, tag = "2")]
        pub device_id: Option<String>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct PlatformSpecificData {
        #[prost(message, optional, tag = "5")]
        pub desktop_linux: Option<NativeDesktopLinuxData>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct NativeDesktopLinuxData {
        #[prost(string, optional, tag = "1")]
        pub system_name: Option<String>,
        #[prost(string, optional, tag = "2")]
        pub system_release: Option<String>,
        #[prost(string, optional, tag = "3")]
        pub system_version: Option<String>,
        #[prost(string, optional, tag = "4")]
        pub hardware: Option<String>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct ClientTokenResponse {
        #[prost(message, optional, tag = "2")]
        pub granted_token: Option<GrantedTokenResponse>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct GrantedTokenResponse {
        #[prost(string, optional, tag = "1")]
        pub token: Option<String>,
        #[prost(int32, optional, tag = "3")]
        pub refresh_after_seconds: Option<i32>,
    }

    // From playlist4_external.proto, with only the fields read here

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct SelectedListContent {
        #[prost(int32, optional, tag = "2")]
        pub length: Option<i32>,
        #[prost(message, optional, tag = "5")]
        pub contents: Option<ListItems>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct ListItems {
        #[prost(message, repeated, tag = "3")]
        pub items: Vec<Item>,
        #[prost(message, repeated, tag = "4")]
        pub meta_items: Vec<MetaItem>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct Item {
        #[prost(string, optional, tag = "1")]
        pub uri: Option<String>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct MetaItem {
        #[prost(message, optional, tag = "2")]
        pub attributes: Option<ListAttributes>,
    }

    #[derive(Clone, PartialEq, prost::Message)]
    pub struct ListAttributes {
        #[prost(string, optional, tag = "1")]
        pub name: Option<String>,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn level_for_public() {
        assert_eq!(level_for(false), proto::PermissionLevel::Blocked);
        assert_eq!(level_for(true), proto::PermissionLevel::Viewer);
    }

    fn public_of(permission: &[u8]) -> Result<bool, DomainError> {
        public_from(Some(decode(permission)?))
    }

    #[test]
    fn asks_for_a_level_as_spotify_clients_do() {
        let request = |public| proto::SetPermissionLevelRequest {
            permission_level: Some(level_for(public).into()),
        };
        assert_eq!(request(false).encode_to_vec(), [0x08, 0x01]);
        assert_eq!(request(true).encode_to_vec(), [0x08, 0x02]);
    }

    #[test]
    fn reads_the_level_of_a_permission() {
        let permission = [
            0x0a, 0x08, 0x34, 0xc5, 0x32, 0x50, 0x01, 0x89, 0x38, 0x17, 0x10, 0x02,
        ];
        assert!(public_of(&permission).unwrap());
        assert!(!public_of(&[0x10, 0x01]).unwrap());
        assert!(public_of(&[0x10, 0x03]).unwrap());
    }

    #[test]
    fn rejects_a_permission_without_a_known_level() {
        let unset = [0x0a, 0x07, b'd', b'e', b'f', b'a', b'u', b'l', b't'];
        assert!(public_of(&unset).is_err());
        assert!(public_of(&[0x10, 0x09]).is_err());
        assert!(public_of(&[0x10, 0x00]).is_err());
        assert!(public_of(&[]).is_err());
        assert!(public_of(&[0x0a, 0x08, 0x34]).is_err());
    }

    #[test]
    fn reads_the_permission_a_change_results_in() {
        let response: proto::SetPermissionResponse =
            decode(&[0x0a, 0x04, 0x0a, 0x00, 0x10, 0x01]).unwrap();
        assert!(!public_from(response.resulting_permission).unwrap());
    }

    #[test]
    fn decodes_folder_names() {
        assert_eq!(decode_group_name("Chill+%26+Lo-fi"), "Chill & Lo-fi");
        assert_eq!(decode_group_name("caf%C3%A9"), "café");
        assert_eq!(decode_group_name("100%"), "100%");
    }

    #[test]
    fn builds_nested_folders() {
        let entry = |uri: &str| (uri.to_string(), None);
        let tree = library_tree(vec![
            entry("spotify:playlist:a"),
            entry("spotify:start-group:f1:Outer"),
            entry("spotify:playlist:b"),
            entry("spotify:start-group:f2:Inner+One"),
            entry("spotify:user:me:playlist:c"),
            entry("spotify:end-group:f2"),
            entry("spotify:end-group:f1"),
            entry("spotify:start-group:f3:Open"),
            entry("spotify:playlist:d"),
        ]);
        let playlist = |id: &str| LibraryNode::Playlist {
            id: id.to_string(),
            name: None,
        };
        assert_eq!(
            tree,
            vec![
                playlist("a"),
                LibraryNode::Folder {
                    id: "f1".into(),
                    name: "Outer".into(),
                    children: vec![
                        playlist("b"),
                        LibraryNode::Folder {
                            id: "f2".into(),
                            name: "Inner One".into(),
                            children: vec![playlist("c")],
                        },
                    ],
                },
                LibraryNode::Folder {
                    id: "f3".into(),
                    name: "Open".into(),
                    children: vec![playlist("d")],
                },
            ]
        );
    }

    #[test]
    fn reads_radio_stations() {
        let body = br#"{"tracks":[{"uri":"spotify:track:a"},{"uri":"spotify:episode:x"},{"uri":"spotify:track:b"}]}"#;
        assert_eq!(track_ids_of_station(body).unwrap(), ["a", "b"]);
    }

    #[test]
    fn reads_synced_lyrics() {
        let body = br#"{"lyrics":{"syncType":"LINE_SYNCED","lines":[
            {"startTimeMs":"1230","words":"Hello","syllables":[]},
            {"startTimeMs":"4000","words":"World"}],"providerDisplayName":"Musixmatch"}}"#;
        let lyrics = parse_lyrics(body).unwrap();
        assert!(lyrics.synced);
        assert_eq!(lyrics.lines.len(), 2);
        assert_eq!(lyrics.lines[0].start_ms, 1230);
        assert_eq!(lyrics.lines[1].words, "World");
        assert_eq!(lyrics.provider.as_deref(), Some("Musixmatch"));
    }

    #[test]
    fn permission_path_escapes_the_id() {
        assert_eq!(
            permission_path("a/b"),
            "/playlist-permission/v1/playlist/a%2Fb/permission/base"
        );
    }
}
