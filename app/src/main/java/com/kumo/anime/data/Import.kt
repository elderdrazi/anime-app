package com.kumo.anime.data

import kotlin.math.roundToInt

/** One row of an AniList list. AniList keeps a row per season, film or special, not per show. */
data class ListRow(
    val mediaId: Int,
    /** AniList's own status: CURRENT, PLANNING, COMPLETED, DROPPED, PAUSED or REPEATING. */
    val status: String,
    val progress: Int,
    /** Out of 100, or 0 when unrated. */
    val score: Int,
    /** Times rewatched. */
    val repeat: Int,
    /** When the row last changed on AniList, in seconds. */
    val updatedAt: Long,
    val adult: Boolean,
) {
    /** Seen all the way through at least once, whatever the episode counter says now. */
    val finished: Boolean get() = status == "COMPLETED" || status == "REPEATING" || repeat > 0
    val inProgress: Boolean get() = status == "CURRENT" || status == "REPEATING" || status == "PAUSED"
    val started: Boolean get() = status != "PLANNING" && status != "DROPPED"
}

/** What an import should save for one show. */
data class ImportPlan(val status: WatchStatus, val seen: Map<Int, Int>, val rating: Int?, val updatedAt: Long)

data class ImportResult(val added: Int = 0, val updated: Int = 0, val leftOut: Int = 0)

/**
 * Works out what one show should look like after bringing in its AniList rows. Null means leave it
 * as it is.
 *
 * Importing only ever adds. Each season keeps whichever episode count is higher, a rating already
 * given in Kumo stays, and a show is never taken off the list. Shows that were only ever dropped on
 * AniList aren't brought in, because Kumo has no "dropped" list to put them on.
 */
fun planImport(series: Series, rows: List<ListRow>, existing: Entry?): ImportPlan? {
    val live = rows.filter { !it.adult }
    if (live.isEmpty()) return null
    if (live.none { it.started }) {
        // Nothing actually being watched: the most this can do is put a new show on "Want to watch".
        if (existing != null || live.none { it.status == "PLANNING" }) return null
        return ImportPlan(WatchStatus.PLANNED, emptyMap(), null, live.maxOf { it.updatedAt } * 1000)
    }

    val had = if (existing == null || existing.status == WatchStatus.PLANNED) emptyMap()
    else series.seenOf(existing.seen, existing.progress)
    val seen = LinkedHashMap<Int, Int>()
    series.watchable.forEachIndexed { i, part ->
        val row = live.firstOrNull { it.mediaId == part.id }
        val len = series.lengthOf(i)
        val theirs = when {
            row == null || row.status == "PLANNING" -> 0
            row.finished -> len ?: row.progress
            else -> len?.let { row.progress.coerceAtMost(it) } ?: row.progress
        }
        val v = maxOf(had[part.id] ?: 0, theirs)
        if (v > 0) seen[part.id] = v
    }

    val status = when {
        series.isComplete(seen) -> WatchStatus.WATCHED
        live.any { it.inProgress } || existing?.status == WatchStatus.WATCHING -> WatchStatus.WATCHING
        // Finished everything they started and stopped there, even if newer seasons exist.
        else -> WatchStatus.WATCHED
    }
    val scores = live.filter { it.score > 0 }.map { it.score }
    val rating = existing?.rating ?: scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.coerceIn(1, 100)

    if (existing != null && existing.whole && existing.status == status && existing.rating == rating && had == seen) return null
    return ImportPlan(status, seen, rating, maxOf(existing?.updatedAt ?: 0, live.maxOf { it.updatedAt } * 1000))
}
