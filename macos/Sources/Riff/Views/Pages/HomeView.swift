import RiffCore
import SwiftUI

/// The home tab: a greeting and shelves for recent listening, top artists,
/// playlists for the moment and season, and the charts. Same shelves and
/// queries as the GTK home page (src/app/components/pages/home).
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var recent: [CardItem] = []
    @State private var topArtists: [CardItem] = []
    @State private var moment: [CardItem] = []
    @State private var season: [CardItem] = []
    @State private var charts: [CardItem] = []
    @State private var loadedAt: Date?

    private static let shelfLength: UInt32 = 10
    private static let staleAfter: TimeInterval = 30 * 60

    var body: some View {
        let now = Date()
        let calendar = Calendar.current
        let part = DayPart(hour: calendar.component(.hour, from: now))
        let momentMood = Mood.moment(weekday: calendar.component(.weekday, from: now), part: part)
        let seasonMood = Mood.season(
            month: calendar.component(.month, from: now),
            day: calendar.component(.day, from: now),
            country: model.user?.country
        )
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                Text(part.greeting)
                    .font(.largeTitle.bold())
                CardShelf(title: String(localized: "Recently Played"), items: recent)
                CardShelf(title: String(localized: "Your Top Artists"), items: topArtists)
                CardShelf(title: momentMood.title, items: moment)
                CardShelf(title: seasonMood.title, items: season)
                CardShelf(title: chartsTitle, items: charts)
                if recent.isEmpty && topArtists.isEmpty && moment.isEmpty && charts.isEmpty {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding(24)
        }
        .navigationTitle("Home")
        .task {
            if let loadedAt, Date().timeIntervalSince(loadedAt) < Self.staleAfter { return }
            loadedAt = Date()
            await load(moment: momentMood, season: seasonMood)
        }
    }

    private var chartsTitle: String {
        if let code = model.user?.country,
           let name = Locale.current.localizedString(forRegionCode: code) {
            return String(localized: "Popular in \(name) and around the world")
        }
        return String(localized: "Charts")
    }

    private func load(moment momentMood: Mood, season seasonMood: Mood) async {
        let core = model.core
        let length = Self.shelfLength
        async let recentItems = (try? await core.recentContexts(limit: length)) ?? []
        async let artists = (try? await core.topArtists(limit: length)) ?? []
        async let momentLists = Self.playlists(core, momentMood.query)
        async let seasonLists = Self.playlists(core, seasonMood.query)
        async let chartLists = Self.charts(core, country: model.user?.country)

        recent = await recentItems.map { item in
            switch item {
            case .album(let album): .album(album)
            case .playlist(let playlist): .playlist(playlist)
            case .artist(let artist): .artist(artist)
            }
        }
        topArtists = await artists.map(CardItem.artist)
        moment = await momentLists.map(CardItem.playlist)
        season = await seasonLists.map(CardItem.playlist)
        charts = await chartLists.map(CardItem.playlist)
    }

    /// Playlists for a query, preferring the ones Spotify made.
    private static func playlists(_ core: RiffCore, _ query: String) async -> [PlaylistInfo] {
        let found = (try? await core.findPlaylists(query: query, limit: shelfLength * 2)) ?? []
        let bySpotify = found.filter { $0.owner?.id == "spotify" }
        let others = found.filter { $0.owner?.id != "spotify" }
        return Array((bySpotify + others).prefix(Int(shelfLength)))
    }

    private static func charts(_ core: RiffCore, country: String?) async -> [PlaylistInfo] {
        if let lists = try? await core.categoryPlaylists(category: "toplists", limit: shelfLength),
           !lists.isEmpty {
            return lists
        }
        var queries: [String] = []
        if let country, let name = Locale(identifier: "en_US").localizedString(forRegionCode: country) {
            queries += ["Top 50 \(name)", "Viral 50 \(name)", "Top Songs \(name)"]
        }
        queries += ["Top 50 Global", "Viral 50 Global", "Today's Top Hits"]
        var found: [PlaylistInfo] = []
        for query in queries {
            let lists = await playlists(core, query)
            let best = lists.first { $0.title.caseInsensitiveCompare(query) == .orderedSame } ?? lists.first
            if let best, !found.contains(where: { $0.id == best.id }) {
                found.append(best)
            }
        }
        return found
    }
}

enum DayPart {
    case morning, afternoon, evening, night

    init(hour: Int) {
        switch hour {
        case 5...11: self = .morning
        case 12...16: self = .afternoon
        case 17...21: self = .evening
        default: self = .night
        }
    }

    var greeting: String {
        switch self {
        case .morning: String(localized: "Good morning")
        case .afternoon: String(localized: "Good afternoon")
        case .evening, .night: String(localized: "Good evening")
        }
    }

    var name: String {
        switch self {
        case .morning: String(localized: "morning")
        case .afternoon: String(localized: "afternoon")
        case .evening: String(localized: "evening")
        case .night: String(localized: "night")
        }
    }
}

/// A shelf of playlists found with a search.
struct Mood {
    let title: String
    let query: String

    /// `weekday` as Foundation counts it: 1 is Sunday, 7 is Saturday.
    static func moment(weekday: Int, part: DayPart) -> Mood {
        let day = Calendar.current.weekdaySymbols[(weekday - 1) % 7]
        let query: String = switch (weekday, part) {
        case (2, .morning): "monday motivation"
        case (6, .evening), (6, .night): "friday night party"
        case (7, .evening), (7, .night): "saturday night"
        case (7, .morning), (1, .morning): "lazy weekend morning"
        case (1, _): "sunday chill"
        case (_, .morning): "morning coffee"
        case (_, .afternoon): "afternoon focus"
        case (_, .evening): "evening chill"
        case (_, .night): "late night vibes"
        }
        return Mood(title: String(localized: "For your \(day) \(part.name)"), query: query)
    }

    private static let southern: Set<String> = ["AR", "AU", "BR", "CL", "NZ", "PE", "PY", "UY", "ZA"]

    static func season(month: Int, day: Int, country: String?) -> Mood {
        if month == 10 && day >= 15 {
            return Mood(title: String(localized: "Halloween Picks"), query: "halloween")
        }
        if month == 12 && day <= 26 {
            return Mood(title: String(localized: "Christmas Songs"), query: "christmas hits")
        }
        let flipped = country.map { southern.contains($0) } ?? false
        let month = flipped ? (month + 5) % 12 + 1 : month
        switch month {
        case 3...5: return Mood(title: String(localized: "Spring Vibes"), query: "spring vibes")
        case 6...8: return Mood(title: String(localized: "Summer Hits"), query: "summer hits")
        case 9...11: return Mood(title: String(localized: "Autumn Vibes"), query: "autumn acoustic")
        default: return Mood(title: String(localized: "Cozy Winter"), query: "cozy winter")
        }
    }
}
