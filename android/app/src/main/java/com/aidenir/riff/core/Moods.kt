package com.aidenir.riff.core

import java.util.Calendar

/** Part of the day, for the home greeting and the moment shelf. */
enum class DayPart {
    Morning, Afternoon, Evening, Night;

    companion object {
        fun of(hour: Int) = when (hour) {
            in 5..11 -> Morning
            in 12..16 -> Afternoon
            in 17..21 -> Evening
            else -> Night
        }
    }
}

/**
 * A shelf of playlists found with a search. Same shelves and queries as the
 * GTK and macOS home pages.
 */
sealed interface Mood {
    val query: String

    /** "For your <day> <part>". */
    data class Moment(val weekday: Int, val part: DayPart, override val query: String) : Mood

    data class Season(val kind: Kind, override val query: String) : Mood {
        enum class Kind { Halloween, Christmas, Spring, Summer, Autumn, Winter }
    }

    companion object {
        /** `weekday` as [Calendar] counts it: 1 is Sunday, 7 is Saturday. */
        fun moment(weekday: Int, part: DayPart): Moment {
            val query = when {
                weekday == Calendar.MONDAY && part == DayPart.Morning -> "monday motivation"
                weekday == Calendar.FRIDAY && part in listOf(DayPart.Evening, DayPart.Night) -> "friday night party"
                weekday == Calendar.SATURDAY && part in listOf(DayPart.Evening, DayPart.Night) -> "saturday night"
                weekday in listOf(Calendar.SATURDAY, Calendar.SUNDAY) && part == DayPart.Morning -> "lazy weekend morning"
                weekday == Calendar.SUNDAY -> "sunday chill"
                part == DayPart.Morning -> "morning coffee"
                part == DayPart.Afternoon -> "afternoon focus"
                part == DayPart.Evening -> "evening chill"
                else -> "late night vibes"
            }
            return Moment(weekday, part, query)
        }

        private val southern = setOf("AR", "AU", "BR", "CL", "NZ", "PE", "PY", "UY", "ZA")

        /** `month` from 1 to 12. */
        fun season(month: Int, day: Int, country: String?): Season {
            if (month == 10 && day >= 15) return Season(Season.Kind.Halloween, "halloween")
            if (month == 12 && day <= 26) return Season(Season.Kind.Christmas, "christmas hits")
            val flipped = country != null && country in southern
            val local = if (flipped) (month + 5) % 12 + 1 else month
            return when (local) {
                in 3..5 -> Season(Season.Kind.Spring, "spring vibes")
                in 6..8 -> Season(Season.Kind.Summer, "summer hits")
                in 9..11 -> Season(Season.Kind.Autumn, "autumn acoustic")
                else -> Season(Season.Kind.Winter, "cozy winter")
            }
        }
    }
}
