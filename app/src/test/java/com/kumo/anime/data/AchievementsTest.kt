package com.kumo.anime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {
    private fun entry(id: Int, status: WatchStatus, progress: Int = 0, total: Int? = null, rating: Int? = null, seen: Map<Int, Int>? = null) =
        Entry(id, "Show $id", null, status, progress, total, 0, whole = true, rating = rating, seen = seen)

    @Test fun `there are exactly a hundred, each with its own id`() {
        assertEquals(100, ACHIEVEMENTS.size)
        assertEquals(100, ACHIEVEMENTS.map { it.id }.toSet().size)
        // Ids are saved as field names, where a dot would mean something else.
        assertTrue(ACHIEVEMENTS.none { '.' in it.id || ' ' in it.id })
    }

    @Test fun `every achievement can be reached and starts locked`() {
        val nothing = evaluate(Stats(), emptySet())
        assertTrue(nothing.unlocked.isEmpty())
        assertEquals(1, nothing.level)
        assertEquals(0, nothing.xp)
        ACHIEVEMENTS.forEach { assertTrue(it.id, it.goal >= 1) }
    }

    @Test fun `levels take more xp each time`() {
        assertEquals(0, xpForLevel(1))
        assertEquals(140, xpForLevel(2))
        assertEquals(1, levelFor(139))
        assertEquals(2, levelFor(140))
        assertTrue((2..80).all { xpForLevel(it + 1) - xpForLevel(it) > xpForLevel(it) - xpForLevel(it - 1) })
    }

    @Test fun `a first episode unlocks its badge and earns xp`() {
        val stats = statsOf(listOf(entry(1, WatchStatus.WATCHING, 1)), emptyMap(), 0, 0, Tracked(), false, false, false, 2026)
        val now = evaluate(stats, emptySet())
        assertEquals(setOf("ep_1"), now.unlocked)
        assertEquals(XP_PER_EPISODE + Tier.BRONZE.xp, now.xp)
    }

    @Test fun `genres, films and eras are counted from what's known about each show`() {
        val entries = (1..6).map { entry(it, WatchStatus.WATCHED, 12, 12) } + entry(7, WatchStatus.PLANNED)
        val meta = (1..5).associateWith { ShowMeta(listOf("Action", "Comedy"), "TV", 2015, 500_000, 80, "Studio A") } +
            (6 to ShowMeta(listOf("Drama"), "MOVIE", 1988, 5_000, 90, "Studio B")) +
            (7 to ShowMeta(listOf("Horror"), "TV", 2020, 100, 50, "Studio C"))
        val s = statsOf(entries, meta, 0, 0, Tracked(), false, false, false, 2026)
        assertEquals(5, s.genres["Action"])
        assertEquals(null, s.genres["Horror"]) // only on Want to watch
        assertEquals(1, s.movies)
        assertEquals(1, s.pre1990)
        assertEquals(1, s.hidden)
        assertEquals(5, s.topStudio)
        assertEquals(2, s.decades)
        val now = evaluate(s, emptySet())
        assertTrue("g_action_5" in now.unlocked)
        assertTrue("movie_1" in now.unlocked)
        assertTrue("vintage_1" in now.unlocked)
        assertTrue("studio_5" in now.unlocked)
        assertFalse("g_action_20" in now.unlocked)
    }

    @Test fun `earned achievements are kept even when the numbers drop`() {
        val now = evaluate(Stats(), setOf("ep_1000"))
        assertTrue("ep_1000" in now.unlocked)
        assertEquals(Tier.GOLD.xp, now.xp)
    }

    @Test fun `reaching a level unlocks the level badges, whose xp counts too`() {
        val now = evaluate(Stats(episodes = 300, completed = 10), emptySet())
        assertTrue(now.level >= 5)
        assertTrue("level_5" in now.unlocked)
        assertEquals(now.level, levelFor(now.xp))
    }

    @Test fun `a full set needs every part of a multi-part show`() {
        val whole = entry(1, WatchStatus.WATCHED, 25, 25, seen = mapOf(1 to 12, 2 to 1, 3 to 12))
        val skipped = entry(2, WatchStatus.WATCHED, 24, 25, seen = mapOf(4 to 12, 6 to 12))
        val single = entry(3, WatchStatus.WATCHED, 12, 12, seen = mapOf(7 to 12))
        assertEquals(1, statsOf(listOf(whole, skipped, single), emptyMap(), 0, 0, Tracked(), false, false, false, 2026).fullSets)
    }

    @Test fun `streaks grow on consecutive days and restart after a gap`() {
        var t = Tracked().logged(1, today = 20261101, yesterday = 20261031, hour = 20)
        assertEquals(1, t.streak)
        t = t.logged(1, 20261101, 20261031, 21)
        assertEquals(1, t.streak)
        assertEquals(2, t.dayEps)
        t = t.logged(1, 20261102, 20261101, 2)
        assertEquals(2, t.streak)
        assertEquals(1, t.dayEps)
        assertEquals(1, t.night)
        t = t.logged(1, 20261105, 20261104, 12)
        assertEquals(1, t.streak)
        assertEquals(2, t.bestStreak)
        assertEquals(2, t.bestDay)
    }

    @Test fun `ticking a whole season at once doesn't count as a day's binge`() {
        val t = Tracked().logged(24, 20261101, 20261031, 12)
        assertEquals(3, t.dayEps)
    }

    @Test fun `borders unlock by level or by achievement count`() {
        assertTrue(Frame.NONE.unlocked(1, 0))
        assertFalse(Frame.GOLD.unlocked(9, 100))
        assertTrue(Frame.GOLD.unlocked(10, 0))
        assertFalse(Frame.TROPHY.unlocked(60, 49))
        assertTrue(Frame.TROPHY.unlocked(1, 50))
        assertEquals(Frame.entries.size, Frame.entries.map { it.key }.toSet().size)
    }
}
