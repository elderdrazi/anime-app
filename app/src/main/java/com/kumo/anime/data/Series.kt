package com.kumo.anime.data

/** One season, film or special inside a show. */
data class Part(
    val id: Int,
    val title: String,
    /** Short name within the show, like "Season 2" or "Mugen Train Arc". */
    val label: String,
    val format: String?,
    val year: Int?,
    /** Episode count, or null when it isn't known yet. */
    val episodes: Int?,
    val released: Boolean,
    /** A proper season, as opposed to a film, OVA or special that people often skip. */
    val season: Boolean = true,
) {
    val meta: String
        get() = listOfNotNull(
            format?.let(::prettyFormat),
            if (released) year?.toString() else year?.let { "Coming $it" } ?: "Coming soon",
            episodes?.takeIf { it > 1 }?.let { "$it eps" },
        ).joinToString(" · ")
}

/** The furthest point someone has reached in a show: which part, and which episode of it. */
data class Position(val index: Int, val part: Part, val episode: Int, val of: Int?)

/**
 * A whole show: every season, film and special in release order.
 *
 * What someone has watched is kept per part, as a map of part id to episodes seen. That lets a
 * film or OVA in the middle stay unwatched while the seasons around it are done.
 */
class Series(val id: Int, val parts: List<Part>) {
    val memberIds: Set<Int> = parts.map { it.id }.toSet()
    val watchable: List<Part> = parts.filter { it.released }

    /** Episodes in each watchable part. Only the newest one may be open-ended (null). */
    private val lengths: List<Int?> = watchable.mapIndexed { i, p ->
        p.episodes ?: if (i == watchable.lastIndex) null else 0
    }

    /** Episodes released across the whole show, or null while the newest season's count is unknown. */
    val total: Int? = if (watchable.isEmpty() || lengths.any { it == null }) null else lengths.sumOf { it ?: 0 }

    val multi: Boolean get() = parts.size > 1

    fun lengthOf(index: Int): Int? = lengths.getOrNull(index)

    /**
     * What's been seen, part by part. Entries saved before 1.3 only kept one running count, which is
     * read as "watched everything in order up to there".
     */
    fun seenOf(saved: Map<Int, Int>?, count: Int): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        if (saved != null) {
            watchable.forEachIndexed { i, p ->
                val v = (saved[p.id] ?: 0).let { v -> lengths[i]?.let { v.coerceAtMost(it) } ?: v }
                if (v > 0) out[p.id] = v
            }
            return out
        }
        var left = count
        watchable.forEachIndexed { i, p ->
            val take = lengths[i]?.let { minOf(left, it) } ?: left
            if (take > 0) out[p.id] = take
            left -= take
        }
        return out
    }

    /** Total episodes seen, for progress bars and comparing with friends. */
    fun count(seen: Map<Int, Int>): Int = watchable.sumOf { seen[it.id] ?: 0 }

    private fun furthest(seen: Map<Int, Int>): Int = watchable.indexOfLast { (seen[it.id] ?: 0) > 0 }

    fun position(seen: Map<Int, Int>): Position? {
        val i = furthest(seen)
        if (i < 0) return null
        return Position(i, watchable[i], seen[watchable[i].id] ?: 0, lengths[i])
    }

    /** Caught up: the newest released part is finished. Skipped films and OVAs don't hold this back. */
    fun isComplete(seen: Map<Int, Int>): Boolean {
        val last = watchable.lastIndex
        if (last < 0) return false
        val len = lengths[last] ?: return false
        return len > 0 && (seen[watchable[last].id] ?: 0) >= len
    }

    /** Everything released, watched. */
    fun all(): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        watchable.forEachIndexed { i, p -> lengths[i]?.takeIf { it > 0 }?.let { out[p.id] = it } }
        return out
    }

    /**
     * Sets how much of one part has been seen, leaving the others alone. When that starts a later
     * season, earlier seasons nobody has ticked are filled in too. Films, OVAs and specials never
     * are: skipping those is normal, so they only count when ticked.
     */
    fun withPart(seen: Map<Int, Int>, index: Int, episodes: Int, fillEarlier: Boolean = true): Map<Int, Int> {
        val part = watchable.getOrNull(index) ?: return seen
        val out = LinkedHashMap(seen)
        val v = lengths[index]?.let { episodes.coerceIn(0, it) } ?: episodes.coerceAtLeast(0)
        if (v == 0) out.remove(part.id) else out[part.id] = v
        if (v > 0 && fillEarlier) {
            for (j in 0 until index) {
                val p = watchable[j]
                val len = lengths[j] ?: continue
                if (p.season && len > 0 && (out[p.id] ?: 0) == 0) out[p.id] = len
            }
        }
        return out
    }

    /** One episode forward or back from the furthest point reached, rolling into the next part at the end of one. */
    fun step(seen: Map<Int, Int>, delta: Int): Map<Int, Int> {
        val i = furthest(seen)
        if (i < 0) return if (delta > 0) withPart(seen, 0, 1, fillEarlier = false) else seen
        val now = seen[watchable[i].id] ?: 0
        val len = lengths[i]
        return if (delta > 0 && len != null && now >= len) {
            if (i < watchable.lastIndex) withPart(seen, i + 1, 1, fillEarlier = false) else seen
        } else {
            withPart(seen, i, now + delta, fillEarlier = false)
        }
    }

    /** One-line position for lists, like "Season 2 · Ep 5". Null for single-season shows. */
    fun label(seen: Map<Int, Int>): String? {
        if (!multi) return null
        val pos = position(seen) ?: return null
        if (pos.of == 1) return pos.part.label
        // Keep the episode visible when the season name is long enough to be cut off.
        return if (pos.part.label.length <= 10) "${pos.part.label} · Ep ${pos.episode}" else "Ep ${pos.episode} · ${pos.part.label}"
    }

    /** Position for notifications, like "Season 2, episode 5". */
    fun describe(seen: Map<Int, Int>): String {
        val pos = position(seen) ?: return "Not started"
        return when {
            !multi -> "Episode ${pos.episode}"
            pos.of == 1 -> pos.part.label
            else -> "${pos.part.label}, episode ${pos.episode}"
        }
    }
}

fun buildSeries(root: Anime, members: List<Anime>): Series {
    val sorted = members.distinctBy { it.id }.sortedWith(compareBy({ it.date }, { it.id }))
    val others = sorted.filter { it.id != root.id }
    val sharesName = others.any { stripShowName(it, root) != null }
    val parts = sorted.map { a ->
        Part(
            id = a.id,
            title = a.title,
            label = when {
                a.id != root.id -> partLabel(root, a)
                root.isSeries && sharesName -> "Season 1"
                else -> root.title
            },
            format = a.format,
            year = a.year,
            episodes = a.episodes,
            released = a.status != "NOT_YET_RELEASED",
            season = a.isSeries,
        )
    }
    return Series(root.id, parts)
}

private val ROMAN = mapOf("ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9)
private val ORDINAL_SEASON = Regex("^(\\d+)(st|nd|rd|th) season$", RegexOption.IGNORE_CASE)

/** A season's name with the show's name taken off the front: "Attack on Titan Season 2" becomes "Season 2". */
fun partLabel(root: Anime, part: Anime): String {
    val rest = stripShowName(part, root) ?: return part.title
    val number = rest.toIntOrNull() ?: ROMAN[rest.lowercase()] ?: ORDINAL_SEASON.find(rest)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        number != null && number in 2..99 && part.isSeries -> "Season $number"
        rest.length < 3 -> part.title
        else -> rest.replaceFirstChar { it.uppercase() }
    }
}

private fun stripShowName(part: Anime, root: Anime): String? {
    val prefixes = listOfNotNull(root.title, root.romaji).filter { it.isNotBlank() }
    for (title in listOfNotNull(part.title, part.romaji)) {
        for (prefix in prefixes) stripPrefix(title, prefix)?.let { return it }
    }
    return null
}

/** Compares letters and digits only, so punctuation differences between season titles don't matter. */
private fun stripPrefix(title: String, prefix: String): String? {
    var i = 0
    var matched = 0
    for (c in prefix) {
        if (!c.isLetterOrDigit()) continue
        while (i < title.length && !title[i].isLetterOrDigit()) i++
        if (i >= title.length || title[i].lowercaseChar() != c.lowercaseChar()) return null
        i++
        matched++
    }
    if (matched == 0) return null
    // "Gintama" shouldn't match the start of an unrelated longer word.
    if (i < title.length && title[i].isLetter() && title[i - 1].isLetter()) return null
    val rest = title.substring(i).trimStart(' ', ':', '-', '–', '—', ',', '.', '!', '?', '~', '\'', '"').trim()
    return rest.takeIf { it.isNotEmpty() }
}
