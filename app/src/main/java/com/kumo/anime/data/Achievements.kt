package com.kumo.anime.data

import kotlinx.serialization.Serializable

/** How hard an achievement is, and what it's worth. */
enum class Tier(val label: String, val xp: Int) {
    BRONZE("Bronze", 50),
    SILVER("Silver", 120),
    GOLD("Gold", 300),
    PLATINUM("Platinum", 750),
}

enum class Group(val title: String) {
    EPISODES("Episodes"),
    SHOWS("Shows finished"),
    RATINGS("Ratings"),
    LIBRARY("Your list"),
    DEDICATION("Dedication"),
    GENRES("Genres"),
    VARIETY("Variety"),
    TASTE("Taste"),
    SOCIAL("Friends"),
    HABITS("Habits"),
    PROFILE("Profile"),
    LEVELS("Levels"),
}

/** One achievement: unlocked once [value] reaches [goal]. Ids are saved, so they never change. */
class Achievement(
    val id: String,
    val title: String,
    val text: String,
    val group: Group,
    val tier: Tier,
    val goal: Int,
    val value: (Stats) -> Int,
)

/** The facts about a show that achievements look at. Kept on the phone, fetched once per show. */
@Serializable
data class ShowMeta(
    val genres: List<String> = emptyList(),
    val format: String? = null,
    val year: Int? = null,
    val popularity: Int? = null,
    val score: Int? = null,
    val studio: String? = null,
)

/** Things that can't be worked out from the list alone, so they're counted as they happen. */
data class Tracked(
    val recs: Int = 0,
    val recsAdded: Int = 0,
    /** The last day an episode was logged, as yyyymmdd. */
    val lastDay: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val dayEps: Int = 0,
    val bestDay: Int = 0,
    val night: Int = 0,
) {
    /**
     * These counters after logging [episodes] more episodes. [today] and [yesterday] are yyyymmdd
     * and [hour] is 0 to 23. Ticking off a whole season in one go counts as a few episodes at
     * most, so the single-day record reflects actual watching.
     */
    fun logged(episodes: Int, today: Int, yesterday: Int, hour: Int): Tracked {
        val counted = episodes.coerceIn(0, 3)
        val sameDay = lastDay == today
        val run = when {
            sameDay -> streak.coerceAtLeast(1)
            lastDay == yesterday -> streak + 1
            else -> 1
        }
        val inDay = if (sameDay) dayEps + counted else counted
        return copy(
            lastDay = today,
            streak = run,
            bestStreak = maxOf(bestStreak, run),
            dayEps = inDay,
            bestDay = maxOf(bestDay, inDay),
            night = if (hour < 4) night + 1 else night,
        )
    }
}

/** Everything the achievements are measured against. */
data class Stats(
    val episodes: Int = 0,
    val completed: Int = 0,
    val rated: Int = 0,
    val perfect: Int = 0,
    val harsh: Int = 0,
    val planned: Int = 0,
    val watching: Int = 0,
    val listSize: Int = 0,
    val longest: Int = 0,
    val fullSets: Int = 0,
    val genres: Map<String, Int> = emptyMap(),
    val movies: Int = 0,
    val shorts: Int = 0,
    val pre2000: Int = 0,
    val pre1990: Int = 0,
    val y2000s: Int = 0,
    val y2010s: Int = 0,
    val thisYear: Int = 0,
    val decades: Int = 0,
    val hidden: Int = 0,
    val acclaimed: Int = 0,
    val panned: Int = 0,
    val topStudio: Int = 0,
    val friends: Int = 0,
    val together: Int = 0,
    val recs: Int = 0,
    val recsAdded: Int = 0,
    val bestStreak: Int = 0,
    val bestDay: Int = 0,
    val night: Int = 0,
    val photo: Boolean = false,
    val email: Boolean = false,
    val anilist: Boolean = false,
    val level: Int = 1,
)

/** Builds the stats from a list and everything known about the person. [year] is the current year. */
fun statsOf(
    entries: List<Entry>, meta: Map<Int, ShowMeta>, friends: Int, together: Int, tracked: Tracked,
    photo: Boolean, email: Boolean, anilist: Boolean, year: Int,
): Stats {
    val started = entries.filter { it.status != WatchStatus.PLANNED }
    val known = started.mapNotNull { meta[it.animeId] }
    val years = known.mapNotNull { it.year }
    val genres = HashMap<String, Int>()
    known.forEach { m -> m.genres.forEach { g -> genres[g] = (genres[g] ?: 0) + 1 } }
    return Stats(
        episodes = started.sumOf { it.progress },
        completed = entries.count { it.status == WatchStatus.WATCHED },
        rated = entries.count { it.rating != null },
        perfect = entries.count { it.rating == 100 },
        harsh = entries.count { (it.rating ?: 100) <= 30 },
        planned = entries.count { it.status == WatchStatus.PLANNED },
        watching = entries.count { it.status == WatchStatus.WATCHING },
        listSize = entries.size,
        longest = started.maxOfOrNull { it.progress } ?: 0,
        // Every season, film and special of a show with more than one part.
        fullSets = started.count { e -> (e.seen?.size ?: 0) >= 2 && e.total != null && e.total > 0 && e.progress >= e.total },
        genres = genres,
        movies = known.count { it.format == "MOVIE" },
        shorts = known.count { it.format == "OVA" || it.format == "SPECIAL" || it.format == "ONA" },
        pre2000 = years.count { it < 2000 },
        pre1990 = years.count { it < 1990 },
        y2000s = years.count { it in 2000..2009 },
        y2010s = years.count { it in 2010..2019 },
        thisYear = years.count { it >= year },
        decades = years.map { it / 10 }.distinct().size,
        hidden = known.count { (it.popularity ?: Int.MAX_VALUE) < 20_000 },
        acclaimed = known.count { (it.score ?: 0) >= 85 },
        panned = known.count { (it.score ?: 100) < 60 },
        topStudio = known.mapNotNull { it.studio }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0,
        friends = friends,
        together = together,
        recs = tracked.recs,
        recsAdded = tracked.recsAdded,
        bestStreak = tracked.bestStreak,
        bestDay = tracked.bestDay,
        night = tracked.night,
        photo = photo,
        email = email,
        anilist = anilist,
    )
}

// ---- levels ----

const val XP_PER_EPISODE = 4
const val XP_PER_SHOW = 60
const val XP_PER_RATING = 10

/** Total XP needed to reach a level. Each level takes a little more than the one before. */
fun xpForLevel(level: Int): Int {
    val n = (level - 1).coerceAtLeast(0)
    return 100 * n + 40 * n * n
}

fun levelFor(xp: Int): Int {
    var level = 1
    while (xpForLevel(level + 1) <= xp) level++
    return level
}

/** The name that goes with a level, like "Binge Watcher". */
fun rankFor(level: Int): String = when {
    level >= 60 -> "Legend"
    level >= 50 -> "Sage"
    level >= 40 -> "Connoisseur"
    level >= 30 -> "Veteran"
    level >= 25 -> "Otaku"
    level >= 20 -> "Devotee"
    level >= 16 -> "Enthusiast"
    level >= 12 -> "Binge Watcher"
    level >= 8 -> "Regular"
    level >= 5 -> "Weekend Watcher"
    level >= 3 -> "Casual Viewer"
    else -> "Newcomer"
}

/** Where someone stands: their stats, what they've unlocked, and the XP and level that adds up to. */
class Standing(val stats: Stats, val unlocked: Set<String>, val xp: Int, val level: Int) {
    /** XP earned inside the current level, and how much the level takes in all. */
    val into: Int get() = xp - xpForLevel(level)
    val span: Int get() = xpForLevel(level + 1) - xpForLevel(level)
}

/**
 * Works out what's unlocked. [already] are achievements earned before; those are kept even if the
 * numbers behind them have since dropped. Levels feed a few achievements whose XP feeds levels, so
 * it goes round until that settles.
 */
fun evaluate(base: Stats, already: Set<String>): Standing {
    var stats = base.copy(level = 1)
    while (true) {
        val unlocked = already + ACHIEVEMENTS.filter { it.value(stats) >= it.goal }.map { it.id }
        val xp = stats.episodes * XP_PER_EPISODE + stats.completed * XP_PER_SHOW + stats.rated * XP_PER_RATING +
            ACHIEVEMENTS.filter { it.id in unlocked }.sumOf { it.tier.xp }
        val level = levelFor(xp)
        if (level <= stats.level) return Standing(stats, unlocked, xp, level)
        stats = stats.copy(level = level)
    }
}

// ---- picture borders ----

/** A border around a profile picture. Each unlocks at a level, or at a number of achievements. */
enum class Frame(val key: String, val label: String, val level: Int = 0, val achievements: Int = 0) {
    NONE("none", "No border"),
    BRONZE("bronze", "Bronze", level = 3),
    SILVER("silver", "Silver", level = 6),
    GOLD("gold", "Gold", level = 10),
    SAKURA("sakura", "Sakura", level = 14),
    OCEAN("ocean", "Ocean", level = 18),
    EMBER("ember", "Ember", level = 22),
    AURORA("aurora", "Aurora", level = 27),
    GALAXY("galaxy", "Galaxy", level = 33),
    PRISM("prism", "Prism", level = 40),
    LEGEND("legend", "Legend", level = 50),
    TROPHY("trophy", "Trophy Hunter", achievements = 50),
    CROWN("crown", "Perfectionist", achievements = 100);

    fun unlocked(level: Int, achievements: Int) = level >= this.level && achievements >= this.achievements

    /** What it takes, for the locked label. */
    val needs: String get() = if (achievements > 0) "$achievements achievements" else "Level $level"

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

// ---- the hundred ----

private fun MutableList<Achievement>.add(
    id: String, title: String, text: String, group: Group, tier: Tier, goal: Int, value: (Stats) -> Int,
) = add(Achievement(id, title, text, group, tier, goal, value))

private fun flag(on: Boolean) = if (on) 1 else 0

val ACHIEVEMENTS: List<Achievement> = buildList {
    // Episodes
    val ep: (Stats) -> Int = { it.episodes }
    add("ep_1", "First Episode", "Watch your first episode.", Group.EPISODES, Tier.BRONZE, 1, ep)
    add("ep_25", "Getting Hooked", "Watch 25 episodes.", Group.EPISODES, Tier.BRONZE, 25, ep)
    add("ep_100", "Century", "Watch 100 episodes.", Group.EPISODES, Tier.BRONZE, 100, ep)
    add("ep_250", "Binge Mode", "Watch 250 episodes.", Group.EPISODES, Tier.SILVER, 250, ep)
    add("ep_500", "Five Hundred Club", "Watch 500 episodes.", Group.EPISODES, Tier.SILVER, 500, ep)
    add("ep_1000", "Thousand-Episode Journey", "Watch 1,000 episodes.", Group.EPISODES, Tier.GOLD, 1000, ep)
    add("ep_2500", "No Sleep Till Finale", "Watch 2,500 episodes.", Group.EPISODES, Tier.GOLD, 2500, ep)
    add("ep_5000", "Living in 2D", "Watch 5,000 episodes.", Group.EPISODES, Tier.PLATINUM, 5000, ep)

    // Shows finished
    val done: (Stats) -> Int = { it.completed }
    add("done_1", "Roll Credits", "Finish your first show.", Group.SHOWS, Tier.BRONZE, 1, done)
    add("done_5", "Finisher", "Finish 5 shows.", Group.SHOWS, Tier.BRONZE, 5, done)
    add("done_10", "Double Digits", "Finish 10 shows.", Group.SHOWS, Tier.BRONZE, 10, done)
    add("done_25", "Seasoned", "Finish 25 shows.", Group.SHOWS, Tier.SILVER, 25, done)
    add("done_50", "Half a Hundred", "Finish 50 shows.", Group.SHOWS, Tier.SILVER, 50, done)
    add("done_100", "Centurion", "Finish 100 shows.", Group.SHOWS, Tier.GOLD, 100, done)
    add("done_200", "Walking Database", "Finish 200 shows.", Group.SHOWS, Tier.GOLD, 200, done)
    add("done_350", "Seen It All", "Finish 350 shows.", Group.SHOWS, Tier.PLATINUM, 350, done)

    // Ratings
    val rated: (Stats) -> Int = { it.rated }
    add("rate_1", "Everyone's a Critic", "Rate a show.", Group.RATINGS, Tier.BRONZE, 1, rated)
    add("rate_10", "Top Ten", "Rate 10 shows and fill your top ten.", Group.RATINGS, Tier.BRONZE, 10, rated)
    add("rate_25", "Opinionated", "Rate 25 shows.", Group.RATINGS, Tier.SILVER, 25, rated)
    add("rate_50", "Judge", "Rate 50 shows.", Group.RATINGS, Tier.SILVER, 50, rated)
    add("rate_100", "Grand Jury", "Rate 100 shows.", Group.RATINGS, Tier.GOLD, 100, rated)
    add("rate_perfect", "Masterpiece", "Give a show a perfect 100.", Group.RATINGS, Tier.BRONZE, 1) { it.perfect }
    add("rate_harsh", "Tough Crowd", "Rate a show 30 or lower.", Group.RATINGS, Tier.BRONZE, 1) { it.harsh }

    // Your list
    add("plan_10", "Watchlist", "Have 10 shows on Want to watch.", Group.LIBRARY, Tier.BRONZE, 10) { it.planned }
    add("plan_50", "Backlog", "Have 50 shows on Want to watch.", Group.LIBRARY, Tier.SILVER, 50) { it.planned }
    add("plan_100", "Mount Backlog", "Have 100 shows on Want to watch.", Group.LIBRARY, Tier.GOLD, 100) { it.planned }
    add("watch_3", "Juggling", "Be watching 3 shows at once.", Group.LIBRARY, Tier.BRONZE, 3) { it.watching }
    add("watch_10", "Seasonal Overload", "Be watching 10 shows at once.", Group.LIBRARY, Tier.SILVER, 10) { it.watching }
    add("list_100", "Collector", "Have 100 shows on your list.", Group.LIBRARY, Tier.SILVER, 100) { it.listSize }
    add("list_300", "Archivist", "Have 300 shows on your list.", Group.LIBRARY, Tier.GOLD, 300) { it.listSize }

    // Dedication
    add("long_50", "Long Runner", "Watch 50 episodes of one show.", Group.DEDICATION, Tier.BRONZE, 50) { it.longest }
    add("long_150", "In It for the Long Haul", "Watch 150 episodes of one show.", Group.DEDICATION, Tier.SILVER, 150) { it.longest }
    add("long_400", "Endurance Run", "Watch 400 episodes of one show.", Group.DEDICATION, Tier.GOLD, 400) { it.longest }
    add("set_1", "Completionist", "Watch every season, film and special of a show.", Group.DEDICATION, Tier.SILVER, 1) { it.fullSets }
    add("set_10", "No Stone Unturned", "Do that for 10 shows.", Group.DEDICATION, Tier.GOLD, 10) { it.fullSets }

    // Genres: a first badge at 5 shows and a second at 20.
    listOf(
        Triple("Action", "Action Fan", "Adrenaline Junkie"),
        Triple("Adventure", "Explorer", "Globetrotter"),
        Triple("Comedy", "Good Laugh", "Class Clown"),
        Triple("Drama", "Feels Trip", "Tear Jerker"),
        Triple("Fantasy", "Spellbound", "Archmage"),
        Triple("Romance", "Hopeless Romantic", "Shipping Captain"),
        Triple("Sci-Fi", "Star Gazer", "Time Traveler"),
        Triple("Slice of Life", "Daily Life", "Cozy Corner"),
        Triple("Mystery", "Sleuth", "Master Detective"),
        Triple("Supernatural", "Ghost Story", "Spirit Medium"),
        Triple("Sports", "Rookie", "MVP"),
        Triple("Horror", "Lights On", "Fearless"),
    ).forEach { (genre, first, second) ->
        val key = genre.lowercase().replace(Regex("[^a-z]"), "")
        val count: (Stats) -> Int = { it.genres[genre] ?: 0 }
        add("g_${key}_5", first, "Watch 5 $genre shows.", Group.GENRES, Tier.BRONZE, 5, count)
        add("g_${key}_20", second, "Watch 20 $genre shows.", Group.GENRES, Tier.SILVER, 20, count)
    }
    add("g_psychological_3", "Mind Games", "Watch 3 Psychological shows.", Group.GENRES, Tier.BRONZE, 3) { it.genres["Psychological"] ?: 0 }
    add("g_thriller_3", "Edge of Your Seat", "Watch 3 Thriller shows.", Group.GENRES, Tier.BRONZE, 3) { it.genres["Thriller"] ?: 0 }
    add("g_mecha_3", "Pilot", "Watch 3 Mecha shows.", Group.GENRES, Tier.BRONZE, 3) { it.genres["Mecha"] ?: 0 }

    // Variety
    val touched: (Stats) -> Int = { it.genres.size }
    add("var_5", "Sampler", "Watch shows from 5 different genres.", Group.VARIETY, Tier.BRONZE, 5, touched)
    add("var_10", "Well Rounded", "Watch shows from 10 different genres.", Group.VARIETY, Tier.SILVER, 10, touched)
    add("var_15", "Omnivore", "Watch shows from 15 different genres.", Group.VARIETY, Tier.GOLD, 15, touched)
    add("movie_1", "Movie Night", "Watch an anime film.", Group.VARIETY, Tier.BRONZE, 1) { it.movies }
    add("movie_10", "Film Buff", "Watch 10 anime films.", Group.VARIETY, Tier.SILVER, 10) { it.movies }
    add("movie_30", "Cinephile", "Watch 30 anime films.", Group.VARIETY, Tier.GOLD, 30) { it.movies }
    add("short_5", "Side Quests", "Watch 5 standalone OVAs, ONAs or specials.", Group.VARIETY, Tier.BRONZE, 5) { it.shorts }
    add("old_1", "Throwback", "Watch a show from before 2000.", Group.VARIETY, Tier.BRONZE, 1) { it.pre2000 }
    add("old_10", "Old School", "Watch 10 shows from before 2000.", Group.VARIETY, Tier.SILVER, 10) { it.pre2000 }
    add("vintage_1", "Vintage", "Watch a show from before 1990.", Group.VARIETY, Tier.SILVER, 1) { it.pre1990 }
    add("y2000_10", "Millennium Kid", "Watch 10 shows from the 2000s.", Group.VARIETY, Tier.SILVER, 10) { it.y2000s }
    add("y2010_25", "Golden Decade", "Watch 25 shows from the 2010s.", Group.VARIETY, Tier.SILVER, 25) { it.y2010s }
    add("new_5", "Fresh Off the Press", "Watch 5 shows from this year.", Group.VARIETY, Tier.BRONZE, 5) { it.thisYear }
    add("decades_4", "Time Capsule", "Watch shows from 4 different decades.", Group.VARIETY, Tier.SILVER, 4) { it.decades }

    // Taste
    add("gem_1", "Hidden Gem", "Watch a show few people know about.", Group.TASTE, Tier.BRONZE, 1) { it.hidden }
    add("gem_10", "Deep Cuts", "Watch 10 shows few people know about.", Group.TASTE, Tier.GOLD, 10) { it.hidden }
    add("acclaimed_10", "Critics' Choice", "Watch 10 shows scored 85% or higher.", Group.TASTE, Tier.SILVER, 10) { it.acclaimed }
    add("panned_3", "Guilty Pleasure", "Watch 3 shows scored under 60%.", Group.TASTE, Tier.BRONZE, 3) { it.panned }
    add("studio_5", "Studio Loyalist", "Watch 5 shows from the same studio.", Group.TASTE, Tier.SILVER, 5) { it.topStudio }

    // Friends
    add("friend_1", "Not Alone", "Add your first friend.", Group.SOCIAL, Tier.BRONZE, 1) { it.friends }
    add("friend_3", "Squad", "Have 3 friends.", Group.SOCIAL, Tier.BRONZE, 3) { it.friends }
    add("friend_10", "Popular", "Have 10 friends.", Group.SOCIAL, Tier.SILVER, 10) { it.friends }
    add("together_1", "Watch Party", "Agree to watch a show together with a friend.", Group.SOCIAL, Tier.BRONZE, 1) { it.together }
    add("together_5", "Regulars", "Agree on 5 shows to watch together.", Group.SOCIAL, Tier.SILVER, 5) { it.together }
    add("rec_1", "You Have to See This", "Recommend a show to a friend.", Group.SOCIAL, Tier.BRONZE, 1) { it.recs }
    add("rec_10", "Tastemaker", "Recommend 10 shows to friends.", Group.SOCIAL, Tier.SILVER, 10) { it.recs }
    add("rec_added_1", "Open Minded", "Add a show a friend recommended.", Group.SOCIAL, Tier.BRONZE, 1) { it.recsAdded }

    // Habits
    add("streak_3", "Three-Day Streak", "Log episodes 3 days in a row.", Group.HABITS, Tier.BRONZE, 3) { it.bestStreak }
    add("streak_7", "Week-Long Streak", "Log episodes 7 days in a row.", Group.HABITS, Tier.SILVER, 7) { it.bestStreak }
    add("streak_30", "Unbroken", "Log episodes 30 days in a row.", Group.HABITS, Tier.PLATINUM, 30) { it.bestStreak }
    add("day_12", "One More Episode", "Log 12 episodes in a single day.", Group.HABITS, Tier.SILVER, 12) { it.bestDay }
    add("night_1", "Night Owl", "Log an episode between midnight and 4 a.m.", Group.HABITS, Tier.BRONZE, 1) { it.night }

    // Profile
    add("photo", "Say Cheese", "Set a profile picture.", Group.PROFILE, Tier.BRONZE, 1) { flag(it.photo) }
    add("email", "Safe and Sound", "Add an email so your list is never lost.", Group.PROFILE, Tier.BRONZE, 1) { flag(it.email) }
    add("anilist", "Linked Up", "Connect an AniList account.", Group.PROFILE, Tier.BRONZE, 1) { flag(it.anilist) }

    // Levels
    val level: (Stats) -> Int = { it.level }
    add("level_5", "Rising Star", "Reach level 5.", Group.LEVELS, Tier.BRONZE, 5, level)
    add("level_10", "Double-Digit Level", "Reach level 10.", Group.LEVELS, Tier.SILVER, 10, level)
    add("level_25", "True Otaku", "Reach level 25.", Group.LEVELS, Tier.GOLD, 25, level)
}

val ACHIEVEMENTS_BY_ID: Map<String, Achievement> = ACHIEVEMENTS.associateBy { it.id }
