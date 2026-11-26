package com.kumo.anime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rules for bringing an AniList list into Kumo, on a show shaped like Season 1, an OVA, Season 2. */
class ImportTest {
    private val s1 = Part(1, "Show", "Season 1", "TV", 2015, 12, released = true)
    private val ova = Part(2, "Show OVA", "OVA", "OVA", 2016, 1, released = true, season = false)
    private val s2 = Part(3, "Show 2", "Season 2", "TV", 2019, 12, released = true)
    private val s3 = Part(4, "Show 3", "Season 3", "TV", 2030, null, released = false)
    private val series = Series(1, listOf(s1, ova, s2, s3))

    private fun row(id: Int, status: String, progress: Int = 0, score: Int = 0, repeat: Int = 0, at: Long = 100, adult: Boolean = false) =
        ListRow(id, status, progress, score, repeat, at, adult)

    private fun entry(status: WatchStatus, seen: Map<Int, Int>, rating: Int? = null, at: Long = 5_000) =
        Entry(1, "Show", null, status, series.count(seen), series.total, at, series.label(seen), whole = true, rating = rating, seen = seen)

    @Test fun `both seasons finished with the OVA skipped is watched, and the OVA stays unticked`() {
        val plan = planImport(series, listOf(row(1, "COMPLETED", 12, 80), row(3, "COMPLETED", 12, 90, at = 200)), null)!!
        assertEquals(WatchStatus.WATCHED, plan.status)
        assertEquals(mapOf(1 to 12, 3 to 12), plan.seen)
        assertEquals(85, plan.rating)
        assertEquals(200_000L, plan.updatedAt)
    }

    @Test fun `partway through a later season is watching`() {
        val plan = planImport(series, listOf(row(1, "COMPLETED", 12), row(3, "CURRENT", 5)), null)!!
        assertEquals(WatchStatus.WATCHING, plan.status)
        assertEquals(mapOf(1 to 12, 3 to 5), plan.seen)
        assertNull(plan.rating)
    }

    @Test fun `finishing only the first season and stopping counts as watched`() {
        val plan = planImport(series, listOf(row(1, "COMPLETED", 12)), null)!!
        assertEquals(WatchStatus.WATCHED, plan.status)
        assertEquals(mapOf(1 to 12), plan.seen)
    }

    @Test fun `planning only goes on want to watch`() {
        val plan = planImport(series, listOf(row(1, "PLANNING")), null)!!
        assertEquals(WatchStatus.PLANNED, plan.status)
        assertEquals(emptyMap<Int, Int>(), plan.seen)
    }

    @Test fun `dropped only is left out`() {
        assertNull(planImport(series, listOf(row(1, "DROPPED", 3)), null))
    }

    @Test fun `adult rows are left out`() {
        assertNull(planImport(series, listOf(row(1, "COMPLETED", 12, adult = true)), null))
    }

    @Test fun `a rewatch in progress still counts the season as seen`() {
        val plan = planImport(series, listOf(row(1, "REPEATING", 2)), null)!!
        assertEquals(mapOf(1 to 12), plan.seen)
        assertEquals(WatchStatus.WATCHING, plan.status)
    }

    @Test fun `progress in Kumo is never set back and its rating is kept`() {
        val mine = entry(WatchStatus.WATCHING, mapOf(1 to 12, 2 to 1, 3 to 8), rating = 70)
        val plan = planImport(series, listOf(row(1, "COMPLETED", 12, 95), row(3, "CURRENT", 5)), mine)
        assertNull(plan)
    }

    @Test fun `new episodes on AniList move Kumo forward`() {
        val mine = entry(WatchStatus.WATCHING, mapOf(1 to 12, 3 to 5))
        val plan = planImport(series, listOf(row(1, "COMPLETED", 12), row(3, "CURRENT", 7, at = 9_000)), mine)!!
        assertEquals(mapOf(1 to 12, 3 to 7), plan.seen)
        assertEquals(WatchStatus.WATCHING, plan.status)
        assertEquals(9_000_000L, plan.updatedAt)
    }

    @Test fun `finishing the newest season on AniList marks the show watched`() {
        val mine = entry(WatchStatus.WATCHING, mapOf(1 to 12, 3 to 11))
        val plan = planImport(series, listOf(row(3, "COMPLETED", 12)), mine)!!
        assertEquals(WatchStatus.WATCHED, plan.status)
    }

    @Test fun `a show already on the list isn't touched by planning or dropped rows`() {
        val mine = entry(WatchStatus.WATCHING, mapOf(1 to 4))
        assertNull(planImport(series, listOf(row(1, "DROPPED", 9)), mine))
        assertNull(planImport(series, listOf(row(3, "PLANNING")), mine))
    }

    @Test fun `want to watch in Kumo becomes watching once started on AniList`() {
        val mine = entry(WatchStatus.PLANNED, emptyMap())
        val plan = planImport(series, listOf(row(1, "CURRENT", 3)), mine)!!
        assertEquals(WatchStatus.WATCHING, plan.status)
        assertEquals(mapOf(1 to 3), plan.seen)
    }

    @Test fun `episode counts past the end of a season are trimmed`() {
        val plan = planImport(series, listOf(row(1, "CURRENT", 40)), null)!!
        assertEquals(mapOf(1 to 12), plan.seen)
    }
}
