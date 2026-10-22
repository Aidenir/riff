// Songs suggested under a playlist, which can be played or added to it.

use gettextrs::gettext;
use gtk::prelude::*;
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use super::PlaylistDetailsModel;
use crate::app::components::utils::decode_px;
use crate::app::components::{add_tracks_to_playlist, DetailsSectionWidget, EventListener};
use crate::app::load;
use crate::app::models::{Track, TrackExt};
use crate::app::state::{load_context, start_actions, BrowserEvent, PlaybackAction};
use crate::app::{AppEvent, ProvidesApi, SongsSource};
use riff_api::Load;

/// Songs suggested at once
const SUGGESTIONS: usize = 10;
/// Artists of the playlist whose top tracks stand in for suggestions
const FALLBACK_ARTISTS: usize = 5;
const ART_SIZE: i32 = 48;

pub struct PlaylistRecommendations {
    model: Rc<PlaylistDetailsModel>,
    section: DetailsSectionWidget,
    list: gtk::ListBox,
    status: gtk::Label,
    // Ids suggested before, not suggested again on refresh
    shown: Rc<RefCell<HashSet<String>>>,
    // The listed rows and their tracks
    rows: Rc<RefCell<Vec<(gtk::ListBoxRow, Track)>>>,
    // Bumped by each fetch, so only the latest one shows
    generation: Rc<Cell<u32>>,
    requested: Cell<bool>,
}

impl PlaylistRecommendations {
    pub fn new(model: Rc<PlaylistDetailsModel>) -> Rc<Self> {
        let list = gtk::ListBox::new();
        list.set_selection_mode(gtk::SelectionMode::None);
        list.add_css_class("boxed-list");
        list.add_css_class("recommendations__list");

        let status = gtk::Label::new(None);
        status.add_css_class("dim-label");
        status.set_margin_top(12);
        status.set_margin_bottom(12);
        status.set_visible(false);

        let content = gtk::Box::new(gtk::Orientation::Vertical, 6);
        let subtitle = gtk::Label::builder()
            // translators: Below the "Recommended Songs" title of a playlist.
            .label(gettext("Based on what's in this playlist"))
            .xalign(0.0)
            .margin_bottom(6)
            .build();
        subtitle.add_css_class("dim-label");
        content.append(&subtitle);
        content.append(&list);
        content.append(&status);

        // translators: Title of the songs suggested at the bottom of a playlist.
        let section = DetailsSectionWidget::new(Some(&gettext("Recommended Songs")), &content);
        section.add_css_class("recommendations");
        section.set_visible(false);

        let refresh = gtk::Button::builder()
            .icon_name("view-refresh-symbolic")
            // translators: Tooltip of the button that suggests other songs for a playlist.
            .tooltip_text(gettext("Refresh"))
            .valign(gtk::Align::Center)
            .build();
        refresh.add_css_class("flat");
        section.set_button(&refresh);

        let this = Rc::new(Self {
            model,
            section,
            list,
            status,
            shown: Default::default(),
            rows: Default::default(),
            generation: Default::default(),
            requested: Cell::new(false),
        });
        refresh.connect_clicked(clone!(
            #[weak]
            this,
            move |_| this.fetch()
        ));
        this.list.connect_row_activated(clone!(
            #[weak]
            this,
            move |_, row| {
                let track = this
                    .rows
                    .borrow()
                    .iter()
                    .find(|(r, _)| r == row)
                    .map(|(_, track)| track.clone());
                if let Some(track) = track {
                    this.play(track);
                }
            }
        ));
        this
    }

    pub fn widget(&self) -> &DetailsSectionWidget {
        &self.section
    }

    /// The loaded tracks of the playlist.
    fn playlist_tracks(&self) -> Vec<Track> {
        self.model
            .app_model
            .get_state()
            .browser
            .playlist_details_state(&self.model.id)
            .map(|state| state.songs.collect())
            .unwrap_or_default()
    }

    fn fetch(self: &Rc<Self>) {
        let tracks = self.playlist_tracks();
        let mut skip: Vec<String> = tracks.iter().map(|t| t.rri.id.clone()).collect();
        skip.extend(self.shown.borrow().iter().cloned());
        let artists = most_common_artists(&tracks, FALLBACK_ARTISTS);
        let id = self.model.id.clone();
        let api = self.model.api_service();
        let tag = load::visible();
        let generation = self.generation.get() + 1;
        self.generation.set(generation);

        self.section.set_visible(true);
        // translators: Shown while suggestions for a playlist load.
        self.show_status(Some(&gettext("Finding songs…")));

        let request = tokio::spawn(async move {
            let suggested = api
                .get_playlist_suggestions(&id, skip.clone(), SUGGESTIONS, tag)
                .await;
            match suggested {
                Ok(tracks) if !tracks.is_empty() => tracks,
                other => {
                    if let Err(e) = other {
                        info!("No playlist suggestions ({e}), using top tracks instead");
                    }
                    top_tracks_of(&api, &artists, &skip, tag).await
                }
            }
        });
        glib::MainContext::default().spawn_local(clone!(
            #[weak(rename_to = this)]
            self,
            async move {
                let tracks = request.await.unwrap_or_default();
                if this.generation.get() == generation {
                    this.show(tracks);
                }
            }
        ));
    }

    fn show_status(&self, status: Option<&str>) {
        self.status.set_label(status.unwrap_or_default());
        self.status.set_visible(status.is_some());
        self.list.set_visible(status.is_none());
    }

    fn show(self: &Rc<Self>, tracks: Vec<Track>) {
        self.list.remove_all();
        self.rows.borrow_mut().clear();
        if tracks.is_empty() {
            // translators: Shown when no songs could be suggested for a playlist.
            self.show_status(Some(&gettext("No recommendations right now")));
            return;
        }
        self.show_status(None);
        let editable = self.model.is_playlist_editable();
        for track in tracks {
            self.shown.borrow_mut().insert(track.rri.id.clone());
            self.list.append(&self.make_row(track, editable));
        }
    }

    fn make_row(self: &Rc<Self>, track: Track, editable: bool) -> gtk::ListBoxRow {
        let art = gtk::Picture::builder()
            .content_fit(gtk::ContentFit::Cover)
            .width_request(ART_SIZE)
            .height_request(ART_SIZE)
            .can_shrink(true)
            .build();
        art.add_css_class("recommendations__art");
        art.set_overflow(gtk::Overflow::Hidden);
        if let Some(url) = track.art.best_for_width(ART_SIZE as u32 * 2) {
            let url = url.to_string();
            let api = self.model.api_service();
            let size = decode_px(ART_SIZE);
            glib::MainContext::default().spawn_local(clone!(
                #[weak]
                art,
                async move {
                    let tag = load::offscreen();
                    if let Some(texture) = api.load_image(&url, size, size, tag).await {
                        art.set_paintable(Some(&texture));
                    }
                }
            ));
        }

        let title = gtk::Label::builder()
            .label(&track.title)
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .build();
        title.add_css_class("heading");
        let artists = gtk::Label::builder()
            .label(track.artists_name())
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .build();
        artists.add_css_class("dim-label");
        let labels = gtk::Box::new(gtk::Orientation::Vertical, 2);
        labels.set_hexpand(true);
        labels.set_valign(gtk::Align::Center);
        labels.append(&title);
        labels.append(&artists);

        let album = gtk::Label::builder()
            .label(track.album.as_ref().map(|a| a.name.as_str()).unwrap_or(""))
            .xalign(0.0)
            .hexpand(true)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .build();
        album.add_css_class("dim-label");

        let content = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        content.set_margin_top(6);
        content.set_margin_bottom(6);
        content.set_margin_start(6);
        content.set_margin_end(6);
        content.append(&art);
        // Equal halves, so the album column lines up across rows
        let columns = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        columns.set_homogeneous(true);
        columns.set_hexpand(true);
        columns.append(&labels);
        columns.append(&album);
        content.append(&columns);

        let row = gtk::ListBoxRow::builder()
            .activatable(true)
            .tooltip_text(&track.title)
            .child(&content)
            .build();

        if editable {
            let add = gtk::Button::builder()
                // translators: Button adding a recommended song to the playlist it is shown under.
                .label(gettext("Add"))
                .valign(gtk::Align::Center)
                .build();
            add.add_css_class("pill");
            add.add_css_class("recommendations__add");
            add.connect_clicked(clone!(
                #[weak(rename_to = this)]
                self,
                #[weak]
                row,
                #[strong]
                track,
                move |_| {
                    add_tracks_to_playlist(
                        &this.model.app_model,
                        &this.model.dispatcher,
                        this.model.id.clone(),
                        vec![track.clone()],
                        None,
                    );
                    this.list.remove(&row);
                    this.rows.borrow_mut().retain(|(r, _)| *r != row);
                }
            ));
            content.append(&add);
        }

        self.rows.borrow_mut().push((row.clone(), track));
        row
    }

    /// Play `track` from its album, as it isn't in the playlist.
    fn play(&self, track: Track) {
        let Some(album) = track.album.clone() else {
            return;
        };
        let source = SongsSource::Album(album.rri.id);
        let load = PlaybackAction::LoadContextSongs(source.clone(), vec![track]);
        self.model.dispatcher.dispatch_many(start_actions(
            false,
            load_context(source, Some(album.name), load),
        ));
    }
}

/// The ids of the `count` artists with the most tracks in `tracks`.
fn most_common_artists(tracks: &[Track], count: usize) -> Vec<String> {
    let mut counts: HashMap<&str, usize> = HashMap::new();
    for artist in tracks.iter().flat_map(|t| t.artists.iter()) {
        if !artist.rri.id.is_empty() {
            *counts.entry(&artist.rri.id).or_default() += 1;
        }
    }
    let mut artists: Vec<(&str, usize)> = counts.into_iter().collect();
    artists.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(b.0)));
    artists
        .into_iter()
        .take(count)
        .map(|(id, _)| id.to_string())
        .collect()
}

/// Top tracks of `artists` not in `skip`, taking turns between the artists.
async fn top_tracks_of(
    api: &riff_api::ApiService,
    artists: &[String],
    skip: &[String],
    tag: Load,
) -> Vec<Track> {
    let skip: HashSet<&str> = skip.iter().map(String::as_str).collect();
    let mut per_artist = Vec::new();
    for artist in artists {
        if let Ok(tracks) = api.get_artist_top_tracks(artist, tag).await {
            let fresh: Vec<Track> = tracks
                .into_iter()
                .filter(|t| !skip.contains(t.rri.id.as_str()))
                .collect();
            per_artist.push(fresh.into_iter());
        }
    }
    let mut picked: Vec<Track> = Vec::new();
    while picked.len() < SUGGESTIONS && !per_artist.is_empty() {
        per_artist.retain_mut(|tracks| match tracks.next() {
            Some(track) => {
                if !picked.iter().any(|p| p.rri.id == track.rri.id) {
                    picked.push(track);
                }
                true
            }
            None => false,
        });
    }
    picked.truncate(SUGGESTIONS);
    picked
}

impl EventListener for Rc<PlaylistRecommendations> {
    fn on_event(&mut self, event: &AppEvent) {
        if let AppEvent::BrowserEvent(BrowserEvent::PlaylistDetailsLoaded(id)) = event {
            if *id == self.model.id && !self.requested.replace(true) {
                self.fetch();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::models::make_track;
    use riff_api::models::{ArtistRef, Provider, ResourceId};

    fn by(id: &str, artists: &[&str]) -> Track {
        let mut track = make_track(id);
        track.artists = artists
            .iter()
            .map(|a| ArtistRef {
                rri: ResourceId::new(Provider::Spotify, *a),
                name: a.to_string(),
            })
            .collect();
        track
    }

    #[test]
    fn picks_the_most_common_artists() {
        let tracks = [by("1", &["a", "b"]), by("2", &["b"]), by("3", &["c", "b"])];
        assert_eq!(most_common_artists(&tracks, 2), ["b", "a"]);
    }
}
