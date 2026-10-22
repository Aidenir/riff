// The home page: shelves of music to pick from. What the user played
// lately, their top artists, the charts of their country, and playlists for
// the time of day, the day of the week and the season.

use gettextrs::gettext;
use gtk::prelude::*;
use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;
use std::sync::Arc;

use crate::app::components::{CardWidget, Component, EventListener, ImageShape, PageHeader};
use crate::app::models::{Album, Artist, CardLayout, CardModel, CardSize, Playlist};
use crate::app::state::{HomePage, LoginEvent, PlaybackEvent, Route, Transition};
use crate::app::{load, AppAction, AppEvent, AppModel, BrowserEvent, Dispatcher, SongsSource};
use riff_api::models::SearchType;
use riff_api::{ApiService, Load};

const SHELF_LENGTH: usize = 10;
/// Most recent contexts remembered across sessions
const RECENT_LENGTH: usize = 20;
/// The page reloads when shown after this long
const STALE_AFTER: std::time::Duration = std::time::Duration::from_secs(30 * 60);

/// Something a card on the home page opens.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Item {
    Playlist(Playlist),
    Album(Album),
    Artist(Artist),
}

impl Item {
    fn id(&self) -> &str {
        match self {
            Self::Playlist(p) => &p.rri.id,
            Self::Album(a) => &a.rri.id,
            Self::Artist(a) => &a.rri.id,
        }
    }

    fn card(&self) -> CardModel {
        match self {
            Self::Playlist(p) => p.into(),
            Self::Album(a) => a.into(),
            Self::Artist(a) => a.into(),
        }
    }

    fn shape(&self) -> ImageShape {
        match self {
            Self::Artist(_) => ImageShape::Round,
            _ => ImageShape::Square,
        }
    }

    fn open(&self) -> AppAction {
        match self {
            Self::Playlist(p) => AppAction::ViewPlaylist(p.rri.id.clone()),
            Self::Album(a) => AppAction::ViewAlbum(a.rri.id.clone()),
            Self::Artist(a) => AppAction::ViewArtist(a.rri.id.clone()),
        }
    }
}

/// A context the user played, as remembered between sessions.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Recent {
    Playlist(String),
    Album(String),
    Artist(String),
}

impl Recent {
    fn from_source(source: &SongsSource) -> Option<Self> {
        match source {
            SongsSource::Playlist(id) => Some(Self::Playlist(id.clone())),
            SongsSource::Album(id) => Some(Self::Album(id.clone())),
            SongsSource::Artist(id) => Some(Self::Artist(id.clone())),
            SongsSource::SavedTracks | SongsSource::Search(_) | SongsSource::Radio(_) => None,
        }
    }

    fn from_uri(uri: &str) -> Option<Self> {
        let mut parts = uri.split(':');
        if parts.next()? != "spotify" {
            return None;
        }
        let kind = parts.next()?;
        // Old playlist URIs name their owner first: spotify:user:<owner>:playlist:<id>
        let (kind, id) = if kind == "user" {
            parts.next()?;
            (parts.next()?, parts.next()?)
        } else {
            (kind, parts.next()?)
        };
        Self::parse(&format!("{kind}:{id}"))
    }

    fn parse(line: &str) -> Option<Self> {
        let (kind, id) = line.trim().split_once(':')?;
        if id.is_empty() {
            return None;
        }
        match kind {
            "playlist" => Some(Self::Playlist(id.to_string())),
            "album" => Some(Self::Album(id.to_string())),
            "artist" => Some(Self::Artist(id.to_string())),
            _ => None,
        }
    }

    fn line(&self) -> String {
        match self {
            Self::Playlist(id) => format!("playlist:{id}"),
            Self::Album(id) => format!("album:{id}"),
            Self::Artist(id) => format!("artist:{id}"),
        }
    }

    async fn resolve(&self, api: &ApiService, tag: Load) -> Option<Item> {
        match self {
            Self::Playlist(id) => api.get_playlist(id, tag).await.ok().map(Item::Playlist),
            Self::Album(id) => api.get_album(id, tag).await.ok().map(Item::Album),
            Self::Artist(id) => api.get_artist(id, tag).await.ok().map(Item::Artist),
        }
    }
}

fn recent_file() -> std::path::PathBuf {
    glib::user_data_dir().join("riff").join("recent-contexts")
}

fn load_recent() -> Vec<Recent> {
    std::fs::read_to_string(recent_file())
        .map(|text| text.lines().filter_map(Recent::parse).collect())
        .unwrap_or_default()
}

fn save_recent(recent: &[Recent]) {
    let path = recent_file();
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let text: Vec<String> = recent.iter().map(Recent::line).collect();
    if let Err(e) = std::fs::write(&path, text.join("\n")) {
        warn!("Could not remember recently played music: {e}");
    }
}

/// Put `item` first, without duplicates, keeping `limit` items.
fn push_front<T: PartialEq>(list: &mut Vec<T>, item: T, limit: usize) {
    list.retain(|i| *i != item);
    list.insert(0, item);
    list.truncate(limit);
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DayPart {
    Morning,
    Afternoon,
    Evening,
    Night,
}

impl DayPart {
    fn at(hour: i32) -> Self {
        match hour {
            5..=11 => Self::Morning,
            12..=16 => Self::Afternoon,
            17..=21 => Self::Evening,
            _ => Self::Night,
        }
    }

    fn greeting(self) -> String {
        match self {
            Self::Morning => gettext("Good morning"),
            Self::Afternoon => gettext("Good afternoon"),
            Self::Evening | Self::Night => gettext("Good evening"),
        }
    }
}

/// A shelf of playlists found with a search.
struct Mood {
    title: String,
    query: &'static str,
}

/// Playlists for the moment: `weekday` from 1 (Monday) to 7 (Sunday).
fn moment_mood(weekday: i32, part: DayPart) -> Mood {
    let day = match weekday {
        1 => gettext("Monday"),
        2 => gettext("Tuesday"),
        3 => gettext("Wednesday"),
        4 => gettext("Thursday"),
        5 => gettext("Friday"),
        6 => gettext("Saturday"),
        _ => gettext("Sunday"),
    };
    let time = match part {
        DayPart::Morning => gettext("morning"),
        DayPart::Afternoon => gettext("afternoon"),
        DayPart::Evening => gettext("evening"),
        DayPart::Night => gettext("night"),
    };
    let query = match (weekday, part) {
        (1, DayPart::Morning) => "monday motivation",
        (5, DayPart::Evening | DayPart::Night) => "friday night party",
        (6, DayPart::Evening | DayPart::Night) => "saturday night",
        (6 | 7, DayPart::Morning) => "lazy weekend morning",
        (7, _) => "sunday chill",
        (_, DayPart::Morning) => "morning coffee",
        (_, DayPart::Afternoon) => "afternoon focus",
        (_, DayPart::Evening) => "evening chill",
        (_, DayPart::Night) => "late night vibes",
    };
    Mood {
        // translators: Title of playlists for the moment, e.g. "For your Thursday evening".
        title: gettext("For your {day} {time}")
            .replace("{day}", &day)
            .replace("{time}", &time),
        query,
    }
}

/// Countries where the seasons are the other way around
const SOUTHERN: &[&str] = &["AR", "AU", "BR", "CL", "NZ", "PE", "PY", "UY", "ZA"];

fn season_mood(month: i32, day: i32, country: Option<&str>) -> Mood {
    let southern = country.is_some_and(|c| SOUTHERN.contains(&c));
    if month == 10 && day >= 15 {
        // translators: Title of playlists for the end of October.
        return Mood {
            title: gettext("Halloween Picks"),
            query: "halloween",
        };
    }
    if month == 12 && day <= 26 {
        // translators: Title of playlists for December.
        return Mood {
            title: gettext("Christmas Songs"),
            query: "christmas hits",
        };
    }
    let month = if southern {
        (month + 5) % 12 + 1
    } else {
        month
    };
    let (title, query) = match month {
        3..=5 => (gettext("Spring Vibes"), "spring vibes"),
        6..=8 => (gettext("Summer Hits"), "summer hits"),
        9..=11 => (gettext("Autumn Vibes"), "autumn acoustic"),
        _ => (gettext("Cozy Winter"), "cozy winter"),
    };
    Mood { title, query }
}

fn country_name(code: &str) -> Option<&'static str> {
    Some(match code {
        "AR" => "Argentina",
        "AT" => "Austria",
        "AU" => "Australia",
        "BE" => "Belgium",
        "BR" => "Brazil",
        "CA" => "Canada",
        "CH" => "Switzerland",
        "CL" => "Chile",
        "CO" => "Colombia",
        "CZ" => "Czech Republic",
        "DE" => "Germany",
        "DK" => "Denmark",
        "EE" => "Estonia",
        "ES" => "Spain",
        "FI" => "Finland",
        "FR" => "France",
        "GB" => "UK",
        "GR" => "Greece",
        "HU" => "Hungary",
        "ID" => "Indonesia",
        "IE" => "Ireland",
        "IN" => "India",
        "IS" => "Iceland",
        "IT" => "Italy",
        "JP" => "Japan",
        "KR" => "South Korea",
        "LT" => "Lithuania",
        "LV" => "Latvia",
        "MX" => "Mexico",
        "NL" => "Netherlands",
        "NO" => "Norway",
        "NZ" => "New Zealand",
        "PH" => "Philippines",
        "PL" => "Poland",
        "PT" => "Portugal",
        "RO" => "Romania",
        "SE" => "Sweden",
        "SG" => "Singapore",
        "TR" => "Turkey",
        "UA" => "Ukraine",
        "US" => "USA",
        "ZA" => "South Africa",
        _ => return None,
    })
}

/// Playlists for `query`, preferring those Spotify made.
async fn search_playlists(api: &ApiService, query: &str, tag: Load) -> Vec<Playlist> {
    let results = api
        .search_scoped(query, SearchType::Playlist, 0, SHELF_LENGTH * 2, tag)
        .await;
    let mut playlists = match results {
        Ok(results) => results.playlists.items,
        Err(e) => {
            warn!("Home: no playlists for \"{query}\": {e}");
            return vec![];
        }
    };
    let by_spotify = |p: &Playlist| p.owner.as_ref().is_some_and(|o| o.rri.id == "spotify");
    playlists.sort_by_key(|p| !by_spotify(p));
    playlists.truncate(SHELF_LENGTH);
    playlists
}

/// The charts of the user's country, and the global ones.
async fn charts(api: &ApiService, country: Option<String>, tag: Load) -> Vec<Playlist> {
    if let Ok(playlists) = api
        .get_category_playlists("toplists", SHELF_LENGTH, tag)
        .await
    {
        if !playlists.is_empty() {
            return playlists;
        }
    }
    let mut queries = Vec::new();
    if let Some(name) = country.as_deref().and_then(country_name) {
        queries.push(format!("Top 50 {name}"));
        queries.push(format!("Viral 50 {name}"));
        queries.push(format!("Top Songs {name}"));
    }
    queries.push("Top 50 Global".to_string());
    queries.push("Viral 50 Global".to_string());
    queries.push("Today's Top Hits".to_string());

    let mut found: Vec<Playlist> = Vec::new();
    for query in queries {
        // The best match of each search
        let playlists = search_playlists(api, &query, tag).await;
        let best = playlists
            .iter()
            .find(|p| p.title.eq_ignore_ascii_case(&query))
            .or(playlists.first());
        if let Some(best) = best {
            if !found.iter().any(|p| p.rri.id == best.rri.id) {
                found.push(best.clone());
            }
        }
    }
    found
}

/// What the user played lately: what Riff remembers, then Spotify's history.
async fn recent_items(api: &ApiService, local: Vec<Recent>, tag: Load) -> Vec<Item> {
    let mut contexts = local;
    match api.get_recently_played(50, tag).await {
        Ok(history) => {
            for uri in history.iter().filter_map(|h| h.context_uri.as_deref()) {
                if let Some(recent) = Recent::from_uri(uri) {
                    if !contexts.contains(&recent) {
                        contexts.push(recent);
                    }
                }
            }
        }
        Err(e) => info!("Home: no listening history from Spotify: {e}"),
    }
    let mut items = Vec::new();
    for recent in contexts {
        if items.len() >= SHELF_LENGTH {
            break;
        }
        if let Some(item) = recent.resolve(api, tag).await {
            items.push(item);
        }
    }
    items
}

struct Shelf {
    root: gtk::Box,
    cards: gtk::Box,
    subtitle: gtk::Label,
}

impl Shelf {
    fn new(title: &str) -> Self {
        let heading = gtk::Label::builder().label(title).xalign(0.0).build();
        heading.add_css_class("title-3");
        let subtitle = gtk::Label::builder().xalign(0.0).visible(false).build();
        subtitle.add_css_class("dim-label");

        let cards = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        cards.add_css_class("home-shelf__cards");
        let scroller = gtk::ScrolledWindow::builder()
            .vscrollbar_policy(gtk::PolicyType::Never)
            .hscrollbar_policy(gtk::PolicyType::Automatic)
            .propagate_natural_height(true)
            .child(&cards)
            .build();

        let root = gtk::Box::new(gtk::Orientation::Vertical, 6);
        root.add_css_class("home-shelf");
        root.append(&heading);
        root.append(&subtitle);
        root.append(&scroller);
        root.set_visible(false);
        Self {
            root,
            cards,
            subtitle,
        }
    }

    fn set_title(&self, title: &str) {
        if let Some(heading) = self.root.first_child().and_downcast::<gtk::Label>() {
            heading.set_label(title);
        }
    }

    fn set_subtitle(&self, subtitle: &str) {
        self.subtitle.set_label(subtitle);
        self.subtitle.set_visible(!subtitle.is_empty());
    }

    fn fill(&self, items: Vec<Item>, api: &Arc<ApiService>, dispatcher: &Dispatcher) {
        while let Some(child) = self.cards.first_child() {
            self.cards.remove(&child);
        }
        let mut seen = HashSet::new();
        for item in items {
            if !seen.insert(item.id().to_string()) {
                continue;
            }
            let card = CardWidget::for_model(
                &item.card(),
                Arc::clone(api),
                item.shape(),
                CardLayout::Vertical,
                CardSize::Medium,
            );
            let button = gtk::Button::builder().child(&card).build();
            button.add_css_class("flat");
            let action = item.open();
            button.connect_clicked(clone!(
                #[strong]
                dispatcher,
                move |_| dispatcher.dispatch(action.clone())
            ));
            self.cards.append(&button);
        }
        self.root.set_visible(self.cards.first_child().is_some());
    }
}

pub struct HomeFeed {
    app_model: Rc<AppModel>,
    dispatcher: Dispatcher,
    root: gtk::ScrolledWindow,
    greeting: gtk::Label,
    recent: Shelf,
    top_artists: Shelf,
    charts: Shelf,
    moment: Shelf,
    season: Shelf,
    empty: libadwaita::StatusPage,
    recent_contexts: RefCell<Vec<Recent>>,
    loaded_at: Cell<Option<std::time::Instant>>,
    // Bumped by each load, so only the latest one fills the shelves
    generation: Rc<Cell<u32>>,
    _header: PageHeader,
}

impl HomeFeed {
    pub fn new(app_model: Rc<AppModel>, dispatcher: Dispatcher, header: PageHeader) -> Rc<Self> {
        header.set_title(&gettext("Home"));

        let greeting = gtk::Label::builder().xalign(0.0).build();
        greeting.add_css_class("home-page__greeting");

        // translators: Title of the albums, artists and playlists the user played lately.
        let recent = Shelf::new(&gettext("Jump Back In"));
        // translators: Title of the artists the user listened to most lately.
        let top_artists = Shelf::new(&gettext("Your Top Artists"));
        // translators: Title of the chart playlists, like "Top 50".
        let charts = Shelf::new(&gettext("Top Charts"));
        let moment = Shelf::new("");
        let season = Shelf::new("");

        let empty = libadwaita::StatusPage::builder()
            .icon_name("go-home-symbolic")
            // translators: Shown on the home page while it loads.
            .title(gettext("Finding Music…"))
            .build();

        let content = gtk::Box::new(gtk::Orientation::Vertical, 32);
        content.set_margin_top(24);
        content.set_margin_bottom(32);
        content.set_margin_start(24);
        content.set_margin_end(24);
        content.append(&greeting);
        content.append(&empty);
        for shelf in [&recent, &top_artists, &moment, &charts, &season] {
            content.append(&shelf.root);
        }
        let clamp = libadwaita::Clamp::builder()
            .maximum_size(1600)
            .tightening_threshold(1600)
            .child(&content)
            .build();
        let root = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .vexpand(true)
            .child(&clamp)
            .build();
        root.add_css_class("home-page");

        let refresh = gtk::Button::builder()
            .icon_name("view-refresh-symbolic")
            // translators: Tooltip of the button that reloads the home page.
            .tooltip_text(gettext("Refresh"))
            .valign(gtk::Align::Center)
            .build();
        header.add_end(&refresh);

        let this = Rc::new(Self {
            app_model,
            dispatcher,
            root,
            greeting,
            recent,
            top_artists,
            charts,
            moment,
            season,
            empty,
            recent_contexts: RefCell::new(load_recent()),
            loaded_at: Cell::new(None),
            generation: Default::default(),
            _header: header,
        });
        refresh.connect_clicked(clone!(
            #[weak]
            this,
            move |_| this.load()
        ));
        this.update_greeting();
        this
    }

    fn update_greeting(&self) {
        let now = glib::DateTime::now_local().ok();
        let hour = now.as_ref().map(|n| n.hour()).unwrap_or(12);
        self.greeting.set_label(&DayPart::at(hour).greeting());
    }

    fn is_logged_in(&self) -> bool {
        self.app_model.get_state().logged_user.user.is_some()
    }

    fn load_if_stale(self: &Rc<Self>) {
        let fresh = self
            .loaded_at
            .get()
            .is_some_and(|at| at.elapsed() < STALE_AFTER);
        if !fresh && self.is_logged_in() {
            self.load();
        }
    }

    fn load(self: &Rc<Self>) {
        self.loaded_at.set(Some(std::time::Instant::now()));
        self.update_greeting();
        let generation = self.generation.get() + 1;
        self.generation.set(generation);

        let now = glib::DateTime::now_local().ok();
        let (hour, weekday, month, day) = now
            .map(|n| (n.hour(), n.day_of_week(), n.month(), n.day_of_month()))
            .unwrap_or((12, 1, 1, 1));
        let api = self.app_model.api();
        let tag = load::visible();
        let local = self.recent_contexts.borrow().clone();

        // Each shelf fills in as soon as its music is found
        let recent = {
            let api = Arc::clone(&api);
            tokio::spawn(async move { recent_items(&api, local, tag).await })
        };
        self.fill_when_ready(generation, recent, |this| &this.recent);

        let top_artists = {
            let api = Arc::clone(&api);
            tokio::spawn(async move {
                let artists = api.get_top_artists(SHELF_LENGTH, tag).await;
                artists
                    .inspect_err(|e| info!("Home: no top artists: {e}"))
                    .unwrap_or_default()
                    .into_iter()
                    .map(Item::Artist)
                    .collect()
            })
        };
        self.fill_when_ready(generation, top_artists, |this| &this.top_artists);

        let moment = moment_mood(weekday, DayPart::at(hour));
        self.moment.set_title(&moment.title);
        let mood_items = |query: &'static str| {
            let api = Arc::clone(&api);
            tokio::spawn(async move {
                search_playlists(&api, query, tag)
                    .await
                    .into_iter()
                    .map(Item::Playlist)
                    .collect()
            })
        };
        self.fill_when_ready(generation, mood_items(moment.query), |this| &this.moment);

        let season = season_mood(month, day, None);
        self.season.set_title(&season.title);
        self.fill_when_ready(generation, mood_items(season.query), |this| &this.season);

        // The charts depend on the user's country
        let charts = {
            let api = Arc::clone(&api);
            tokio::spawn(async move {
                let country = api
                    .get_current_user(tag)
                    .await
                    .ok()
                    .and_then(|user| user.country);
                let playlists = charts(&api, country.clone(), tag).await;
                let items: Vec<Item> = playlists.into_iter().map(Item::Playlist).collect();
                (items, country)
            })
        };
        glib::MainContext::default().spawn_local(clone!(
            #[weak(rename_to = this)]
            self,
            async move {
                let Ok((items, country)) = charts.await else {
                    return;
                };
                if this.generation.get() != generation {
                    return;
                }
                let place = country.as_deref().and_then(country_name);
                if let Some(place) = place {
                    // translators: Subtitle of the charts on the home page, e.g. "Popular in Sweden and around the world".
                    this.charts.set_subtitle(
                        &gettext("Popular in {} and around the world").replace("{}", place),
                    );
                }
                this.fill(&this.charts, items);
                // Seasons are flipped south of the equator
                let season = season_mood(month, day, country.as_deref());
                this.season.set_title(&season.title);
            }
        ));
    }

    fn fill_when_ready(
        self: &Rc<Self>,
        generation: u32,
        items: tokio::task::JoinHandle<Vec<Item>>,
        shelf: fn(&HomeFeed) -> &Shelf,
    ) {
        glib::MainContext::default().spawn_local(clone!(
            #[weak(rename_to = this)]
            self,
            async move {
                let Ok(items) = items.await else {
                    return;
                };
                if this.generation.get() != generation {
                    return;
                }
                this.fill(shelf(&this), items);
            }
        ));
    }

    fn fill(&self, shelf: &Shelf, items: Vec<Item>) {
        shelf.fill(items, &self.app_model.api(), &self.dispatcher);
        let any = [
            &self.recent,
            &self.top_artists,
            &self.charts,
            &self.moment,
            &self.season,
        ]
        .iter()
        .any(|shelf| shelf.root.is_visible());
        self.empty.set_visible(!any);
    }

    /// Remember what started playing, for "Jump Back In".
    fn remember_source(&self) {
        let source = self
            .app_model
            .get_state()
            .playback
            .current_source()
            .cloned();
        let Some(recent) = source.as_ref().and_then(Recent::from_source) else {
            return;
        };
        let mut contexts = self.recent_contexts.borrow_mut();
        if contexts.first() == Some(&recent) {
            return;
        }
        push_front(&mut contexts, recent, RECENT_LENGTH);
        save_recent(&contexts);
    }
}

impl Component for Rc<HomeFeed> {
    fn get_root_widget(&self) -> &gtk::Widget {
        self.root.upcast_ref()
    }
}

impl EventListener for Rc<HomeFeed> {
    fn on_event(&mut self, event: &AppEvent) {
        match event {
            AppEvent::LoginEvent(LoginEvent::LoginCompleted) => self.load(),
            AppEvent::BrowserEvent(BrowserEvent::NavigationChanged(Transition {
                to: Route::Home(HomePage::Home),
                ..
            })) => self.load_if_stale(),
            AppEvent::PlaybackEvent(PlaybackEvent::SourceChanged) => self.remember_source(),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_context_uris() {
        assert_eq!(
            Recent::from_uri("spotify:playlist:abc"),
            Some(Recent::Playlist("abc".into()))
        );
        assert_eq!(
            Recent::from_uri("spotify:user:me:playlist:abc"),
            Some(Recent::Playlist("abc".into()))
        );
        assert_eq!(
            Recent::from_uri("spotify:album:x"),
            Some(Recent::Album("x".into()))
        );
        assert_eq!(Recent::from_uri("spotify:show:x"), None);
        assert_eq!(Recent::from_uri("nope"), None);
    }

    #[test]
    fn remembers_contexts_as_lines() {
        for recent in [
            Recent::Playlist("a".into()),
            Recent::Album("b".into()),
            Recent::Artist("c".into()),
        ] {
            assert_eq!(Recent::parse(&recent.line()), Some(recent));
        }
    }

    #[test]
    fn keeps_recent_contexts_unique_and_bounded() {
        let mut list = vec![1, 2, 3];
        push_front(&mut list, 3, 3);
        assert_eq!(list, [3, 1, 2]);
        push_front(&mut list, 4, 3);
        assert_eq!(list, [4, 3, 1]);
    }

    #[test]
    fn picks_moods_for_the_moment() {
        assert_eq!(moment_mood(5, DayPart::Evening).query, "friday night party");
        assert_eq!(moment_mood(2, DayPart::Morning).query, "morning coffee");
        assert_eq!(DayPart::at(23), DayPart::Night);
        assert_eq!(DayPart::at(3), DayPart::Night);
        assert_eq!(DayPart::at(9), DayPart::Morning);
    }

    #[test]
    fn flips_seasons_in_the_south() {
        assert_eq!(season_mood(7, 1, Some("SE")).query, "summer hits");
        assert_eq!(season_mood(7, 1, Some("AU")).query, "cozy winter");
        assert_eq!(season_mood(10, 22, Some("SE")).query, "halloween");
    }
}
