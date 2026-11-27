package com.kumo.anime.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendPicksTest {
    private val ann = Friend("a", "ann")
    private val bo = Friend("b", "bo")
    private val cy = Friend("c", "cy")

    private fun entry(id: Int, rating: Int?, status: WatchStatus = WatchStatus.WATCHED) =
        Entry(id, "Show $id", "cover$id", status, 12, 12, 0, whole = true, rating = rating)

    @Test fun `ratings are averaged per show, keenest friend named first`() {
        val picks = friendPicks(listOf(ann to entry(1, 80), bo to entry(1, 95), cy to entry(2, 60), ann to entry(3, null)))
        assertEquals(setOf(1, 2), picks.keys)
        assertEquals(88, picks[1]!!.average)
        assertEquals(2, picks[1]!!.count)
        assertEquals(listOf("bo", "ann"), picks[1]!!.names)
        assertEquals("Show 1", picks[1]!!.title)
    }

    @Test fun `favorites leave out low averages and put unwatched shows first`() {
        val picks = friendPicks(
            listOf(
                ann to entry(1, 100), // I've finished this one
                ann to entry(2, 90), bo to entry(2, 90), // two friends agree
                cy to entry(3, 92), // one friend, slightly higher
                bo to entry(4, 50), // not well rated
                ann to entry(5, 85), // I'm watching it
            ),
        )
        val mine = listOf(entry(1, null), entry(5, null, WatchStatus.WATCHING), entry(3, null, WatchStatus.PLANNED))
        assertEquals(listOf(2, 3, 5, 1), friendFavorites(picks.values, mine).map { it.animeId })
    }

    @Test fun `the common categories lead and the rest follow in A to Z order`() {
        val labels = FILTERS.map { it.label }
        assertEquals(listOf("Action", "Shounen", "Romance", "Comedy", "Fantasy", "Isekai"), labels.take(6))
        val rest = labels.drop(10)
        assertEquals(rest.sorted(), rest)
        assertEquals(42, labels.toSet().size)
    }
}
