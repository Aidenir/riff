//! Lyrics from LRCLIB (https://lrclib.net), an open database of synced
//! lyrics, for tracks the music provider has none for.

use crate::error::DomainError;
use crate::models::{Lyrics, LyricsLine, Track};

const GET_URL: &str = "https://lrclib.net/api/get";

pub async fn get_lyrics(track: &Track) -> Result<Lyrics, DomainError> {
    let artist = track.artists.first().map(|a| a.name.as_str()).unwrap_or("");
    let album = track.album.as_ref().map(|a| a.name.as_str()).unwrap_or("");
    let duration = (track.duration_ms / 1000).to_string();
    let request = crate::http::api_client()
        .get(GET_URL)
        .query(&[
            ("track_name", track.title.as_str()),
            ("artist_name", artist),
            ("album_name", album),
            ("duration", duration.as_str()),
        ])
        .header(
            reqwest::header::USER_AGENT,
            "Riff (https://github.com/Diegovsky/riff)",
        );
    let response = request
        .send()
        .await
        .map_err(|e| DomainError::Network(e.to_string()))?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(DomainError::NotFound {
            resource: "lrclib lyrics".to_string(),
        });
    }
    if !response.status().is_success() {
        return Err(DomainError::ServerError {
            status: response.status().as_u16(),
            message: "lrclib".to_string(),
        });
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|e| DomainError::Network(e.to_string()))?;
    let body: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|e| DomainError::Parse(format!("lrclib: {e}")))?;
    parse(&body)
}

fn parse(body: &serde_json::Value) -> Result<Lyrics, DomainError> {
    let text = |key: &str| {
        body.get(key)
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
    };
    let lyrics = if let Some(synced) = text("syncedLyrics") {
        Lyrics {
            synced: true,
            lines: parse_lrc(synced),
            provider: Some("LRCLIB".to_string()),
        }
    } else if let Some(plain) = text("plainLyrics") {
        Lyrics {
            synced: false,
            lines: plain
                .lines()
                .map(|words| LyricsLine {
                    start_ms: 0,
                    words: words.to_string(),
                })
                .collect(),
            provider: Some("LRCLIB".to_string()),
        }
    } else {
        return Err(DomainError::NotFound {
            resource: "lrclib lyrics".to_string(),
        });
    };
    Ok(lyrics)
}

/// Lines like `[01:02.34] words`, possibly with several times each.
fn parse_lrc(lrc: &str) -> Vec<LyricsLine> {
    let mut lines = Vec::new();
    for line in lrc.lines() {
        let mut rest = line.trim();
        let mut starts = Vec::new();
        while let Some(tag) = rest.strip_prefix('[') {
            let Some((time, after)) = tag.split_once(']') else {
                break;
            };
            match parse_time(time) {
                Some(ms) => starts.push(ms),
                // Metadata like [ar: ...]
                None => {}
            }
            rest = after;
        }
        for start_ms in starts {
            lines.push(LyricsLine {
                start_ms,
                words: rest.trim().to_string(),
            });
        }
    }
    lines.sort_by_key(|l| l.start_ms);
    lines
}

fn parse_time(time: &str) -> Option<u32> {
    let (minutes, seconds) = time.split_once(':')?;
    let minutes: u32 = minutes.trim().parse().ok()?;
    let seconds: f64 = seconds.trim().parse().ok()?;
    Some(minutes * 60_000 + (seconds * 1000.0).round() as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_lrc() {
        let lines =
            parse_lrc("[ar: Someone]\n[00:12.50] Hello\n[01:02.00][00:05.00] Again\n[00:20.00]");
        let starts: Vec<u32> = lines.iter().map(|l| l.start_ms).collect();
        assert_eq!(starts, [5000, 12500, 20000, 62000]);
        assert_eq!(lines[0].words, "Again");
        assert_eq!(lines[2].words, "");
    }

    #[test]
    fn prefers_synced_lyrics() {
        let body = serde_json::json!({"syncedLyrics": "[00:01.00] a", "plainLyrics": "a"});
        assert!(parse(&body).unwrap().synced);
        let body = serde_json::json!({"syncedLyrics": null, "plainLyrics": "a\nb"});
        let lyrics = parse(&body).unwrap();
        assert!(!lyrics.synced);
        assert_eq!(lyrics.lines.len(), 2);
        assert!(parse(&serde_json::json!({"instrumental": true})).is_err());
    }
}
