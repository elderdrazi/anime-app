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
) {
    val meta: String
        get() = listOfNotNull(
            format?.let(::prettyFormat),
            if (released) year?.toString() else year?.let { "Coming $it" } ?: "Coming soon",
            episodes?.takeIf { it > 1 }?.let { "$it eps" },
        ).joinToString(" · ")
}

/** Where an episode count lands inside a show. */
data class Position(val index: Int, val part: Part, val episode: Int, val of: Int?)

/**
 * A whole show: every season, film and special in release order.
 * Progress through it is one running count of episodes watched across all of them.
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

    fun offsetOf(index: Int): Int = lengths.take(index).sumOf { it ?: 0 }

    fun lengthOf(index: Int): Int? = lengths.getOrNull(index)

    fun clamp(progress: Int): Int = total?.let { progress.coerceIn(0, it) } ?: progress.coerceAtLeast(0)

    /** The season and episode that a running count of [progress] episodes ends on. */
    fun locate(progress: Int): Position? {
        if (progress <= 0 || watchable.isEmpty()) return null
        var before = 0
        watchable.forEachIndexed { i, p ->
            val len = lengths[i]
            if (len == null || progress <= before + len) return Position(i, p, progress - before, len)
            before += len
        }
        val last = watchable.lastIndex
        return Position(last, watchable[last], lengths[last] ?: 0, lengths[last])
    }

    /** One-line position for lists, like "Season 2 · Ep 5". Null for single-season shows. */
    fun label(progress: Int): String? {
        if (!multi) return null
        val pos = locate(progress) ?: return null
        if (pos.of == 1) return pos.part.label
        // Keep the episode visible when the season name is long enough to be cut off.
        return if (pos.part.label.length <= 10) "${pos.part.label} · Ep ${pos.episode}" else "Ep ${pos.episode} · ${pos.part.label}"
    }

    /** Position for notifications and friend rows, like "Season 2, episode 5". */
    fun describe(progress: Int): String {
        val pos = locate(progress) ?: return "Not started"
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
            episodes = a.episodes ?: a.aired?.takeIf { a.status == "FINISHED" },
            released = a.status != "NOT_YET_RELEASED",
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
