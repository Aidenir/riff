// The lyrics of the current track, following playback line by line.

use gettextrs::gettext;
use gtk::prelude::*;
use libadwaita::prelude::*;
use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::{Duration, Instant};

use crate::app::components::utils::decode_px;
use crate::app::components::{art_tint, Component, EventListener, PageHeader};
use crate::app::models::{Lyrics, Track, TrackExt};
use crate::app::state::{PlaybackAction, PlaybackEvent};
use crate::app::{load, AppEvent, AppModel, Dispatcher};
use riff_api::DomainError;

const ART_SIZE: i32 = 72;
/// After the user scrolls, the current line is not followed for this long
const MANUAL_SCROLL_GRACE: Duration = Duration::from_secs(4);
const SCROLL_DURATION_MS: u32 = 450;

const ACTIVE_CLASS: &str = "lyrics-line--active";
const PAST_CLASS: &str = "lyrics-line--past";

/// The line sung at `position_ms`, if any started yet.
pub fn line_at(lyrics: &Lyrics, position_ms: u64) -> Option<usize> {
    let started = lyrics
        .lines
        .partition_point(|line| u64::from(line.start_ms) <= position_ms);
    started.checked_sub(1)
}

struct Shown {
    track_id: Option<String>,
    lyrics: Option<Lyrics>,
    lines: Vec<gtk::Label>,
    active: Option<usize>,
}

pub struct LyricsPage {
    app_model: Rc<AppModel>,
    dispatcher: Dispatcher,
    root: gtk::Stack,
    scroller: gtk::ScrolledWindow,
    page: gtk::Box,
    lines_box: gtk::Box,
    status: libadwaita::StatusPage,
    art: gtk::Picture,
    title: gtk::Label,
    artist: gtk::Label,
    footer: gtk::Label,
    shown: Rc<RefCell<Shown>>,
    last_manual_scroll: Rc<Cell<Option<Instant>>>,
    scroll_animation: Rc<RefCell<Option<libadwaita::TimedAnimation>>>,
    header: PageHeader,
}

impl LyricsPage {
    pub fn new(app_model: Rc<AppModel>, dispatcher: Dispatcher, header: PageHeader) -> Rc<Self> {
        // translators: Title of the page showing the lyrics of the current track.
        header.set_title(&gettext("Lyrics"));

        let art = gtk::Picture::builder()
            .content_fit(gtk::ContentFit::Cover)
            .width_request(ART_SIZE)
            .height_request(ART_SIZE)
            .can_shrink(true)
            .overflow(gtk::Overflow::Hidden)
            .build();
        art.add_css_class("lyrics__art");
        let title = gtk::Label::builder().xalign(0.0).wrap(true).build();
        title.add_css_class("title-2");
        let artist = gtk::Label::builder().xalign(0.0).wrap(true).build();
        artist.add_css_class("dim-label");
        let labels = gtk::Box::new(gtk::Orientation::Vertical, 4);
        labels.set_valign(gtk::Align::Center);
        labels.append(&title);
        labels.append(&artist);
        let track_box = gtk::Box::new(gtk::Orientation::Horizontal, 18);
        track_box.append(&art);
        track_box.append(&labels);

        let lines_box = gtk::Box::new(gtk::Orientation::Vertical, 18);
        lines_box.add_css_class("lyrics__lines");

        let footer = gtk::Label::builder().xalign(0.0).wrap(true).build();
        footer.add_css_class("dim-label");
        footer.add_css_class("caption");

        let content = gtk::Box::new(gtk::Orientation::Vertical, 36);
        content.set_margin_top(32);
        content.set_margin_bottom(96);
        content.set_margin_start(24);
        content.set_margin_end(24);
        content.append(&track_box);
        content.append(&lines_box);
        content.append(&footer);

        let clamp = libadwaita::Clamp::builder()
            .maximum_size(820)
            .tightening_threshold(820)
            .child(&content)
            .build();
        let page = gtk::Box::new(gtk::Orientation::Vertical, 0);
        page.add_css_class("lyrics-page");
        page.append(&clamp);

        let scroller = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .vexpand(true)
            .hexpand(true)
            .child(&page)
            .build();

        let status = libadwaita::StatusPage::builder()
            .icon_name("lyrics-symbolic")
            .build();

        let root = gtk::Stack::new();
        root.set_transition_type(gtk::StackTransitionType::Crossfade);
        root.add_named(&scroller, Some("lyrics"));
        root.add_named(&status, Some("status"));

        let this = Rc::new(Self {
            app_model,
            dispatcher,
            root,
            scroller,
            page,
            lines_box,
            status,
            art,
            title,
            artist,
            footer,
            shown: Rc::new(RefCell::new(Shown {
                track_id: None,
                lyrics: None,
                lines: vec![],
                active: None,
            })),
            last_manual_scroll: Default::default(),
            scroll_animation: Default::default(),
            header,
        });
        this.connect_manual_scroll();
        this.follow_playback();
        this.load_current();
        this
    }

    // A scroll by the user stops following the lyrics for a moment
    fn connect_manual_scroll(&self) {
        let scroll = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::VERTICAL);
        scroll.connect_scroll(clone!(
            #[strong(rename_to = last)]
            self.last_manual_scroll,
            #[strong(rename_to = animation)]
            self.scroll_animation,
            move |_, _, _| {
                last.set(Some(Instant::now()));
                if let Some(animation) = animation.borrow_mut().take() {
                    animation.pause();
                }
                glib::Propagation::Proceed
            }
        ));
        self.scroller.add_controller(scroll);
    }

    // The active line is looked up on each frame while the page shows
    fn follow_playback(self: &Rc<Self>) {
        let weak = Rc::downgrade(self);
        self.scroller.add_tick_callback(move |_, _| {
            let Some(this) = weak.upgrade() else {
                return glib::ControlFlow::Break;
            };
            this.sync_active_line();
            glib::ControlFlow::Continue
        });
    }

    fn sync_active_line(&self) {
        let position = self.app_model.get_state().playback.position_ms();
        let mut shown = self.shown.borrow_mut();
        let Some(lyrics) = shown.lyrics.as_ref().filter(|l| l.synced) else {
            return;
        };
        let active = line_at(lyrics, position);
        if active == shown.active {
            return;
        }
        shown.active = active;
        for (i, label) in shown.lines.iter().enumerate() {
            let is_active = Some(i) == active;
            let is_past = active.is_some_and(|a| i < a);
            if label.has_css_class(ACTIVE_CLASS) != is_active {
                if is_active {
                    label.add_css_class(ACTIVE_CLASS);
                } else {
                    label.remove_css_class(ACTIVE_CLASS);
                }
            }
            if label.has_css_class(PAST_CLASS) != is_past {
                if is_past {
                    label.add_css_class(PAST_CLASS);
                } else {
                    label.remove_css_class(PAST_CLASS);
                }
            }
        }
        let label = active.and_then(|a| shown.lines.get(a)).cloned();
        drop(shown);
        if let Some(label) = label {
            self.scroll_to(&label);
        }
    }

    /// Bring `label` to the upper middle of the page.
    fn scroll_to(&self, label: &gtk::Label) {
        let recently_scrolled = self
            .last_manual_scroll
            .get()
            .is_some_and(|at| at.elapsed() < MANUAL_SCROLL_GRACE);
        if recently_scrolled {
            return;
        }
        let Some(point) = label.compute_point(&self.page, &gtk::graphene::Point::new(0.0, 0.0))
        else {
            return;
        };
        let adjustment = self.scroller.vadjustment();
        let target = (point.y() as f64 + label.height() as f64 / 2.0
            - adjustment.page_size() * 0.4)
            .clamp(0.0, (adjustment.upper() - adjustment.page_size()).max(0.0));
        let target_fn = libadwaita::CallbackAnimationTarget::new(clone!(
            #[weak]
            adjustment,
            move |value| adjustment.set_value(value)
        ));
        let animation = libadwaita::TimedAnimation::new(
            &self.scroller,
            adjustment.value(),
            target,
            SCROLL_DURATION_MS,
            target_fn,
        );
        animation.set_easing(libadwaita::Easing::EaseOutCubic);
        animation.play();
        if let Some(previous) = self.scroll_animation.replace(Some(animation)) {
            previous.pause();
        }
    }

    fn show_status(&self, title: &str, description: Option<&str>) {
        self.status.set_title(title);
        self.status.set_description(description);
        self.root.set_visible_child_name("status");
    }

    fn load_current(self: &Rc<Self>) {
        let track = self.app_model.get_state().playback.current_song();
        let Some(track) = track else {
            self.shown.borrow_mut().track_id = None;
            // translators: Shown on the lyrics page when nothing plays.
            self.show_status(&gettext("No Track Playing"), None);
            return;
        };
        if self.shown.borrow().track_id.as_ref() == Some(&track.rri.id) {
            return;
        }
        {
            let mut shown = self.shown.borrow_mut();
            shown.track_id = Some(track.rri.id.clone());
            shown.lyrics = None;
            shown.active = None;
        }
        self.show_track(&track);
        // translators: Shown on the lyrics page while the lyrics load.
        self.show_status(&gettext("Loading Lyrics…"), None);

        let api = self.app_model.api();
        let tag = load::visible();
        let for_request = track.clone();
        let request = tokio::spawn(async move { api.get_lyrics(&for_request, tag).await });
        let weak = Rc::downgrade(self);
        let id = track.rri.id;
        glib::MainContext::default().spawn_local(async move {
            let result = request
                .await
                .unwrap_or_else(|e| Err(DomainError::Network(e.to_string())));
            let Some(this) = weak.upgrade() else {
                return;
            };
            if this.shown.borrow().track_id.as_ref() != Some(&id) {
                return;
            }
            match result {
                Ok(lyrics) if !lyrics.lines.is_empty() => this.show_lyrics(lyrics),
                Ok(_) | Err(DomainError::NotFound { .. }) => this.show_status(
                    // translators: Shown on the lyrics page for a track without lyrics.
                    &gettext("No Lyrics"),
                    // translators: Shown on the lyrics page for a track without lyrics.
                    Some(&gettext("There are no lyrics for this track")),
                ),
                Err(e) => {
                    warn!("Could not load lyrics: {e}");
                    this.show_status(
                        // translators: Shown on the lyrics page when they could not be loaded.
                        &gettext("Lyrics Unavailable"),
                        // translators: Shown on the lyrics page when they could not be loaded.
                        Some(&gettext("The lyrics could not be loaded")),
                    )
                }
            }
        });
    }

    fn show_track(&self, track: &Track) {
        self.title.set_label(&track.title);
        self.artist.set_label(&track.artists_name());
        self.art.set_paintable(None::<&gdk::Paintable>);
        art_tint::clear_tint(&self.page);
        let Some(url) = track.art.best_for_width(300) else {
            return;
        };
        let url = url.to_string();
        let api = self.app_model.api();
        let size = decode_px(ART_SIZE);
        let tag = load::visible();
        glib::MainContext::default().spawn_local(clone!(
            #[weak(rename_to = art)]
            self.art,
            #[weak(rename_to = page)]
            self.page,
            async move {
                if let Some(texture) = api.load_image(&url, size, size, tag).await {
                    art.set_paintable(Some(&texture));
                    art_tint::tint_with_art(&page, &texture);
                }
            }
        ));
    }

    fn show_lyrics(&self, lyrics: Lyrics) {
        while let Some(child) = self.lines_box.first_child() {
            self.lines_box.remove(&child);
        }
        let mut lines = Vec::with_capacity(lyrics.lines.len());
        for line in &lyrics.lines {
            // An empty line is an instrumental pause
            let words = if line.words.trim().is_empty() {
                "♪"
            } else {
                line.words.as_str()
            };
            let label = gtk::Label::builder()
                .label(words)
                .xalign(0.0)
                .wrap(true)
                .wrap_mode(gtk::pango::WrapMode::WordChar)
                .build();
            label.add_css_class("lyrics-line");
            if lyrics.synced {
                label.set_cursor_from_name(Some("pointer"));
                let start = line.start_ms;
                let click = gtk::GestureClick::new();
                click.connect_released(clone!(
                    #[strong(rename_to = dispatcher)]
                    self.dispatcher,
                    #[strong(rename_to = last)]
                    self.last_manual_scroll,
                    move |_, _, _, _| {
                        // Follow the lyrics again from there
                        last.set(None);
                        dispatcher.dispatch(PlaybackAction::Seek(start).into());
                    }
                ));
                label.add_controller(click);
            }
            self.lines_box.append(&label);
            lines.push(label);
        }

        let mut footer = Vec::new();
        if !lyrics.synced {
            // translators: Shown under lyrics that can't follow playback.
            footer.push(gettext("These lyrics aren't synced to the track."));
        }
        if let Some(provider) = &lyrics.provider {
            // translators: Credits the source of the lyrics, e.g. "Lyrics provided by Musixmatch".
            footer.push(gettext("Lyrics provided by {}").replace("{}", provider));
        }
        self.footer.set_label(&footer.join("\n"));
        self.footer.set_visible(!footer.is_empty());
        if lyrics.synced {
            self.page.remove_css_class("lyrics--unsynced");
        } else {
            self.page.add_css_class("lyrics--unsynced");
        }

        {
            let mut shown = self.shown.borrow_mut();
            shown.lines = lines;
            shown.lyrics = Some(lyrics);
            shown.active = None;
        }
        self.last_manual_scroll.set(None);
        self.scroller.vadjustment().set_value(0.0);
        self.root.set_visible_child_name("lyrics");
    }
}

impl Component for Rc<LyricsPage> {
    fn get_root_widget(&self) -> &gtk::Widget {
        self.root.upcast_ref()
    }
}

impl EventListener for Rc<LyricsPage> {
    fn on_event(&mut self, event: &AppEvent) {
        match event {
            AppEvent::PlaybackEvent(
                PlaybackEvent::TrackChanged(_) | PlaybackEvent::PlaybackStopped,
            ) => self.load_current(),
            AppEvent::PlaybackEvent(
                PlaybackEvent::TrackSeeked(_) | PlaybackEvent::SeekSynced(_),
            ) => {
                // A jump: follow the lyrics there
                self.last_manual_scroll.set(None);
            }
            _ => {}
        }
    }
}

impl Drop for LyricsPage {
    fn drop(&mut self) {
        self.header.remove();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use riff_api::models::LyricsLine;

    #[test]
    fn finds_the_line_being_sung() {
        let line = |start_ms| LyricsLine {
            start_ms,
            words: String::new(),
        };
        let lyrics = Lyrics {
            synced: true,
            lines: vec![line(1000), line(3000), line(5000)],
            provider: None,
        };
        assert_eq!(line_at(&lyrics, 0), None);
        assert_eq!(line_at(&lyrics, 1000), Some(0));
        assert_eq!(line_at(&lyrics, 4999), Some(1));
        assert_eq!(line_at(&lyrics, 60_000), Some(2));
    }
}
