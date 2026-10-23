//! User settings for the native clients.
//!
//! The GTK app keeps these in GSettings (`dev.diegovsky.Riff.gschema.xml`).
//! Here they live in one JSON file in the app's support folder, with the same
//! keys and defaults, so the core can read them from any thread.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use librespot::playback::config::{AudioFormat, Bitrate, NormalisationMethod, NormalisationType};
use riff_api::RepeatMode;
use riff_player::{AudioBackend, SpotifyPlayerSettings, VolumeCurveType};
use serde::{Deserialize, Serialize};

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ThemePreference {
    Light,
    Dark,
    System,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum CloseWindowBehavior {
    Ask,
    KeepPlaying,
    Quit,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum StreamBitrate {
    Kbps96,
    Kbps160,
    Kbps320,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum SampleFormat {
    S16,
    S24,
    S24x3,
    S32,
    F32,
    F64,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum VolumeCurve {
    Log,
    Linear,
    Cubic,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum NormalisationKind {
    Auto,
    Track,
    Album,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum NormalisationMode {
    Dynamic,
    Basic,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum FeatureFlagKey {
    DeviceSelector,
    Normalisation,
    PinnedObjects,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct FeatureFlagInfo {
    pub key: FeatureFlagKey,
    pub title: String,
    pub description: String,
    pub enabled: bool,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppSettings {
    // General
    pub theme: ThemePreference,
    pub queue_side_panel: bool,
    pub close_window_behavior: CloseWindowBehavior,
    pub inhibit_suspend: bool,
    pub skip_explicit: bool,
    pub shuffle_separation: u32,
    // Audio
    pub bitrate: StreamBitrate,
    pub audio_format: SampleFormat,
    pub gapless_playback: bool,
    pub volume_curve: VolumeCurve,
    pub mono_audio: bool,
    // Normalisation
    pub normalisation: bool,
    pub normalisation_type: NormalisationKind,
    pub normalisation_method: NormalisationMode,
    pub normalisation_pregain_db: f64,
    pub normalisation_threshold_dbfs: f64,
    pub normalisation_attack_ms: f64,
    pub normalisation_release_ms: f64,
    pub normalisation_knee_db: f64,
    // Audio engine
    pub eq_bands: Vec<f64>,
    pub pan: f64,
    pub pitch_cents: f64,
    // Network
    pub ap_port: u32,
    // Caching
    pub memory_cache_size_mb: u32,
    pub disk_cache_size_mb: u32,
    pub audio_cache_size_mb: u32,
    // Playback state restored on launch
    pub volume: f64,
    pub shuffle: bool,
    pub repeat: crate::models::Repeat,
    // Library presentation
    pub sort_albums: String,
    pub sort_playlists: String,
    pub sort_artists: String,
    // Feature flags
    pub feature_device_selector: bool,
    pub feature_normalisation: bool,
    pub feature_pinned_objects: bool,
    /// Pinned sidebar items, as `kind:id` strings.
    pub pinned: Vec<String>,
    pub drm_verified_user: String,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            theme: ThemePreference::System,
            queue_side_panel: false,
            close_window_behavior: CloseWindowBehavior::Ask,
            inhibit_suspend: true,
            skip_explicit: false,
            shuffle_separation: 2,
            bitrate: StreamBitrate::Kbps160,
            audio_format: SampleFormat::S16,
            gapless_playback: true,
            volume_curve: VolumeCurve::Log,
            mono_audio: false,
            normalisation: false,
            normalisation_type: NormalisationKind::Auto,
            normalisation_method: NormalisationMode::Dynamic,
            normalisation_pregain_db: 0.0,
            normalisation_threshold_dbfs: -2.0,
            normalisation_attack_ms: 5.0,
            normalisation_release_ms: 100.0,
            normalisation_knee_db: 5.0,
            eq_bands: vec![0.0; 10],
            pan: 0.0,
            pitch_cents: 0.0,
            ap_port: 0,
            memory_cache_size_mb: 32,
            disk_cache_size_mb: 256,
            audio_cache_size_mb: 1024,
            volume: 0.7,
            shuffle: false,
            repeat: crate::models::Repeat::Off,
            sort_albums: "recently-added".into(),
            sort_playlists: "recently-added".into(),
            sort_artists: "recently-added".into(),
            feature_device_selector: false,
            feature_normalisation: false,
            feature_pinned_objects: false,
            pinned: vec![],
            drm_verified_user: String::new(),
        }
    }
}

impl AppSettings {
    pub fn eq_array(&self) -> [f64; 10] {
        let mut bands = [0.0; 10];
        for (slot, value) in bands.iter_mut().zip(self.eq_bands.iter()) {
            *slot = value.clamp(-12.0, 12.0);
        }
        bands
    }

    pub fn player_settings(&self) -> SpotifyPlayerSettings {
        SpotifyPlayerSettings {
            bitrate: match self.bitrate {
                StreamBitrate::Kbps96 => Bitrate::Bitrate96,
                StreamBitrate::Kbps160 => Bitrate::Bitrate160,
                StreamBitrate::Kbps320 => Bitrate::Bitrate320,
            },
            backend: AudioBackend::System,
            gapless: self.gapless_playback,
            ap_port: match self.ap_port {
                1..=65535 => Some(self.ap_port as u16),
                _ => None,
            },
            shuffle: self.shuffle,
            repeat: self.repeat.into(),
            volume: self.volume,
            skip_explicit: self.skip_explicit,
            shuffle_separation: self.shuffle_separation,
            volume_curve: match self.volume_curve {
                VolumeCurve::Log => VolumeCurveType::Log,
                VolumeCurve::Linear => VolumeCurveType::Linear,
                VolumeCurve::Cubic => VolumeCurveType::Cubic,
            },
            normalisation: self.normalisation,
            normalisation_type: match self.normalisation_type {
                NormalisationKind::Auto => NormalisationType::Auto,
                NormalisationKind::Track => NormalisationType::Track,
                NormalisationKind::Album => NormalisationType::Album,
            },
            normalisation_method: match self.normalisation_method {
                NormalisationMode::Dynamic => NormalisationMethod::Dynamic,
                NormalisationMode::Basic => NormalisationMethod::Basic,
            },
            normalisation_pregain_db: self.normalisation_pregain_db,
            normalisation_threshold_dbfs: self.normalisation_threshold_dbfs,
            normalisation_attack_ms: self.normalisation_attack_ms,
            normalisation_release_ms: self.normalisation_release_ms,
            normalisation_knee_db: self.normalisation_knee_db,
            audio_format: match self.audio_format {
                SampleFormat::S16 => AudioFormat::S16,
                SampleFormat::S24 => AudioFormat::S24,
                SampleFormat::S24x3 => AudioFormat::S24_3,
                SampleFormat::S32 => AudioFormat::S32,
                SampleFormat::F32 => AudioFormat::F32,
                SampleFormat::F64 => AudioFormat::F64,
            },
            mono_audio: self.mono_audio,
            pan: self.pan.clamp(-1.0, 1.0),
            pitch_cents: self.pitch_cents.clamp(-100.0, 100.0),
            eq_bands: self.eq_array(),
        }
    }

    pub fn repeat_mode(&self) -> RepeatMode {
        self.repeat.into()
    }

    pub fn feature_flags(&self) -> Vec<FeatureFlagInfo> {
        use riff_config::feature_flags::FeatureFlag;
        FeatureFlag::ALL
            .iter()
            .map(|flag| {
                let (key, enabled) = match flag {
                    FeatureFlag::DeviceSelector => {
                        (FeatureFlagKey::DeviceSelector, self.feature_device_selector)
                    }
                    FeatureFlag::Normalisation => {
                        (FeatureFlagKey::Normalisation, self.feature_normalisation)
                    }
                    FeatureFlag::PinnedObjects => {
                        (FeatureFlagKey::PinnedObjects, self.feature_pinned_objects)
                    }
                };
                FeatureFlagInfo {
                    key,
                    title: flag.title().to_string(),
                    description: flag.description().to_string(),
                    enabled,
                }
            })
            .collect()
    }
}

/// Settings loaded once and written back on every change.
pub struct SettingsStore {
    path: PathBuf,
    current: Mutex<AppSettings>,
}

impl SettingsStore {
    pub fn open(support_dir: &Path) -> Self {
        let path = support_dir.join("settings.json");
        let current = std::fs::read(&path)
            .ok()
            .and_then(|bytes| {
                serde_json::from_slice::<AppSettings>(&bytes)
                    .map_err(|e| warn!("Ignoring unreadable settings file: {e}"))
                    .ok()
            })
            .unwrap_or_default();
        Self {
            path,
            current: Mutex::new(current),
        }
    }

    pub fn get(&self) -> AppSettings {
        self.current.lock().unwrap().clone()
    }

    /// Replace the settings, returning the previous value.
    pub fn set(&self, settings: AppSettings) -> AppSettings {
        let previous = std::mem::replace(&mut *self.current.lock().unwrap(), settings.clone());
        self.save(&settings);
        previous
    }

    pub fn update(&self, change: impl FnOnce(&mut AppSettings)) -> AppSettings {
        let mut settings = self.get();
        change(&mut settings);
        self.set(settings.clone());
        settings
    }

    fn save(&self, settings: &AppSettings) {
        if let Some(parent) = self.path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match serde_json::to_vec_pretty(settings) {
            Ok(json) => {
                let tmp = self.path.with_extension("json.tmp");
                if let Err(e) =
                    std::fs::write(&tmp, json).and_then(|_| std::fs::rename(&tmp, &self.path))
                {
                    warn!("Failed to save settings: {e}");
                }
            }
            Err(e) => warn!("Failed to encode settings: {e}"),
        }
    }
}

/// Keys whose change needs the librespot player to be rebuilt, matching
/// `RESTART_REQUIRED_KEYS` on Linux except that a rebuild is done live.
pub fn requires_player_reload(old: &AppSettings, new: &AppSettings) -> bool {
    old.player_settings()
        .requires_reload(&new.player_settings())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_round_trip_through_json() {
        let settings = AppSettings::default();
        let json = serde_json::to_string(&settings).unwrap();
        let back: AppSettings = serde_json::from_str(&json).unwrap();
        assert_eq!(settings, back);
    }

    #[test]
    fn missing_keys_fall_back_to_defaults() {
        let back: AppSettings = serde_json::from_str(r#"{"volume": 0.25}"#).unwrap();
        assert_eq!(back.volume, 0.25);
        assert_eq!(back.shuffle_separation, 2);
        assert_eq!(back.eq_bands.len(), 10);
    }

    #[test]
    fn player_settings_clamp_and_map() {
        let settings = AppSettings {
            eq_bands: vec![20.0, -20.0],
            ap_port: 443,
            ..Default::default()
        };
        let player = settings.player_settings();
        assert_eq!(player.eq_bands[0], 12.0);
        assert_eq!(player.eq_bands[1], -12.0);
        assert_eq!(player.eq_bands[2], 0.0);
        assert_eq!(player.ap_port, Some(443));
        assert_eq!(player.backend, AudioBackend::System);
    }
}
