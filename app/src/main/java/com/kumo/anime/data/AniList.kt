package com.kumo.anime.data

import android.content.Context
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class Sort(
    val api: String,
    val label: String,
    val statuses: List<String>? = null,
    val minPopularity: Int? = null,
) {
    POPULAR("POPULARITY_DESC", "Popular"),
    TRENDING("TRENDING_DESC", "Trending"),
    TOP("SCORE_DESC", "Top rated"),
    AIRING("TRENDING_DESC", "Airing", listOf("RELEASING")),
    UPCOMING("POPULARITY_DESC", "Upcoming", listOf("NOT_YET_RELEASED")),
}

/** A browse category: one of AniList's genres, or one of its finer-grained tags. */
data class Filter(val label: String, val tag: Boolean = false, val api: String = label)

/** The categories people reach for most. These lead the row; everything else follows in A to Z order. */
private val COMMON_FILTERS = listOf(
    "Action", "Shounen", "Romance", "Comedy", "Fantasy", "Isekai", "Adventure", "Drama", "Slice of Life", "Sci-Fi",
)

val FILTERS: List<Filter> = (
    listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha",
        "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports",
        "Supernatural", "Thriller",
    ).map { Filter(it) } + listOf(
        Filter("Isekai", true), Filter("Shounen", true), Filter("Seinen", true), Filter("Shoujo", true),
        Filter("Josei", true), Filter("School", true), Filter("Magic", true), Filter("Military", true),
        Filter("Historical", true), Filter("Space", true), Filter("Samurai", true), Filter("Vampire", true),
        Filter("Demons", true), Filter("Super Power", true), Filter("Martial Arts", true),
        Filter("Survival", true), Filter("Detective", true), Filter("Time Travel", true, "Time Manipulation"),
        Filter("Cyberpunk", true), Filter("Idol", true), Filter("Iyashikei", true), Filter("Parody", true),
        Filter("Zombie", true), Filter("Kids", true),
    )
    ).sortedWith(
        compareBy<Filter> { f -> COMMON_FILTERS.indexOf(f.label).let { if (it < 0) COMMON_FILTERS.size else it } }
            .thenBy { it.label },
    )

const val NO_DATE = 99999999

/** One AniList entry. A show is its first season; later seasons hang off it as [Part]s. */
data class Anime(
    val id: Int,
    val title: String,
    val romaji: String?,
    val nativeTitle: String?,
    val cover: String?,
    val banner: String?,
    val color: String?,
    val genres: List<String>,
    val score: Int?,
    val popularity: Int?,
    val episodes: Int?,
    val year: Int?,
    val format: String?,
    val status: String?,
    val studio: String?,
    val description: String?,
    /** Release date as yyyymmdd, or [NO_DATE] when unannounced. */
    val date: Int,
    /** Episodes out so far, when the entry is still airing. */
    val aired: Int?,
    /** False when only the card fields (title, cover, score) are loaded. */
    val full: Boolean,
) {
    val meta: String
        get() = listOfNotNull(
            format?.let(::prettyFormat),
            year?.toString(),
            episodes?.let { if (it == 1) "1 ep" else "$it eps" },
        ).joinToString(" · ")

    /** A multi-episode series, as opposed to a film, OVA or special. */
    val isSeries: Boolean
        get() = format == "TV" || format == "TV_SHORT" || (format == "ONA" && (episodes ?: 2) > 1)
}

fun prettyFormat(f: String) = when (f) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> f.lowercase().replaceFirstChar { it.uppercase() }
}

/** A card in Discover: the show, plus the season that matched when it wasn't the first one. */
data class Show(val anime: Anime, val via: Anime?)

data class ShowPage(val items: List<Show>, val hasNext: Boolean)

/** Someone's AniList list: the username as AniList spells it, and one row per season, film or special. */
data class UserList(val name: String, val rows: List<ListRow>)

class AniListException(message: String) : Exception(message)

/**
 * Client for the public AniList GraphQL API (no key needed).
 *
 * AniList lists every season, film and special as its own entry, joined by prequel/sequel links.
 * This client folds those into shows: each entry is traced back to the first season of its story,
 * and that first season stands for the whole show everywhere in the app.
 */
object AniList {
    private const val URL = "https://graphql.anilist.co"
    private const val MAP_TTL = 14L * 24 * 60 * 60 * 1000
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private class Node {
        @Volatile var anime: Anime? = null
        /** Linked entries by id; null until this entry's own links have been loaded. */
        @Volatile var prequels: List<Int>? = null
        @Volatile var sequels: List<Int>? = null
    }

    private val nodes = ConcurrentHashMap<Int, Node>()
    private val roots = ConcurrentHashMap<Int, Int>()
    private val seriesCache = ConcurrentHashMap<Int, Series>()
    private var mapFile: File? = null
    @Volatile private var savedRoots = 0

    private const val MIN = "id type format episodes startDate { year month day }"
    private const val CARD = "id type format episodes status seasonYear averageScore" +
        " startDate { year month day } title { romaji english native }" +
        " coverImage { extraLarge large color } nextAiringEpisode { episode }"
    private const val LINKS = "relations { edges { relationType(version: 2) node { $CARD } } }"
    private const val DEEP_LINKS = "relations { edges { relationType(version: 2) node { $CARD" +
        " relations { edges { relationType(version: 2) node { $MIN } } } } } }"
    private const val FULL = "$CARD bannerImage genres popularity" +
        " studios(isMain: true) { nodes { name } } description(asHtml: true)"

    /** Restores the entry-to-show map from the last run, so repeat visits skip the lookups. */
    fun init(ctx: Context) {
        bannerFile = File(ctx.filesDir, "banners.txt").also { saved ->
            runCatching {
                if (saved.exists()) saved.forEachLine { line ->
                    val at = line.indexOf('=')
                    val id = if (at > 0) line.substring(0, at).toIntOrNull() else null
                    if (id != null) bannerUrls[id] = line.substring(at + 1)
                }
            }
        }
        val f = File(ctx.filesDir, "show-map.txt")
        mapFile = f
        runCatching {
            if (!f.exists()) return
            val lines = f.readLines()
            val created = lines.firstOrNull()?.toLongOrNull() ?: 0
            if (System.currentTimeMillis() - created > MAP_TTL) {
                f.delete()
                return
            }
            lines.drop(1).forEach { line ->
                val (a, b) = line.split('=').takeIf { it.size == 2 } ?: return@forEach
                roots[a.toIntOrNull() ?: return@forEach] = b.toIntOrNull() ?: return@forEach
            }
            savedRoots = roots.size
        }
    }

    private fun saveRoots() {
        val f = mapFile ?: return
        if (roots.size == savedRoots) return
        runCatching {
            val created = f.takeIf { it.exists() }?.useLines { it.firstOrNull() }?.toLongOrNull()
                ?: System.currentTimeMillis()
            f.writeText(buildString {
                append(created).append('\n')
                roots.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
            })
            savedRoots = roots.size
        }
    }

    // ---- browsing ----

    suspend fun browse(page: Int, sort: Sort, filter: Filter?, search: String?): ShowPage {
        val searching = !search.isNullOrBlank()
        val query = "query (\$page: Int, \$sort: [MediaSort], \$genre: String, \$tag: String, \$rank: Int," +
            " \$search: String, \$status: [MediaStatus], \$skip: [MediaFormat], \$pop: Int) {" +
            " Page(page: \$page, perPage: 50) { pageInfo { hasNextPage }" +
            " media(type: ANIME, isAdult: false, sort: \$sort, genre: \$genre, tag: \$tag, minimumTagRank: \$rank," +
            " search: \$search, status_in: \$status, format_not_in: \$skip, popularity_greater: \$pop)" +
            " { $FULL $LINKS } } }"
        val vars = buildJsonObject {
            put("page", page)
            putJsonArray("sort") { add(if (searching) "SEARCH_MATCH" else sort.api) }
            if (filter != null && !filter.tag) put("genre", filter.api)
            if (filter != null && filter.tag) {
                put("tag", filter.api)
                put("rank", 50)
            }
            if (searching) {
                put("search", search!!.trim())
            } else {
                // Music videos are in AniList's anime list; keep them out of browsing but findable by name.
                putJsonArray("skip") { add("MUSIC") }
                sort.statuses?.let { st -> putJsonArray("status") { st.forEach { add(it) } } }
                sort.minPopularity?.let { put("pop", it) }
            }
        }
        val p = post(query, vars).page ?: throw AniListException("AniList sent an empty page")
        p.media.forEach { register(it, full = true) }
        val ids = p.media.map { it.id }
        val rootOf = rootsOf(ids)
        ensureCards(rootOf.values)
        val shows = LinkedHashMap<Int, Show>()
        for (id in ids) {
            val rootId = rootOf[id] ?: id
            val root = nodes[rootId]?.anime ?: continue
            val via = if (rootId == id) null else nodes[id]?.anime
            val old = shows[rootId]
            // Prefer the show's own entry as the match when it's on this page too.
            if (old == null) shows[rootId] = Show(root, via) else if (via == null && old.via != null && searching) shows[rootId] = Show(root, null)
        }
        return ShowPage(shows.values.toList(), p.pageInfo.hasNextPage)
    }

    // ---- shows ----

    /** The show a known entry belongs to, if it's already loaded. */
    fun cachedShow(id: Int): Anime? = roots[id]?.let { nodes[it]?.anime }

    /** Card details for one entry, if loaded. */
    fun cachedEntry(id: Int): Anime? = nodes[id]?.anime?.takeIf { it.title.isNotEmpty() }

    fun cachedSeries(showId: Int): Series? = seriesCache[showId]

    /** Full details for the show that [id] belongs to; [id] can be any of its seasons. */
    suspend fun show(id: Int): Anime {
        val rootId = rootsOf(listOf(id))[id] ?: id
        nodes[rootId]?.anime?.takeIf { it.full }?.let { return it }
        load(listOf(rootId))
        return nodes[rootId]?.anime?.takeIf { it.full } ?: throw AniListException("That anime couldn't be found")
    }

    /** Every season, film and special of a show, in release order. */
    suspend fun series(showId: Int): Series {
        seriesCache[showId]?.let { return it }
        if (nodes[showId]?.prequels == null || nodes[showId]?.anime == null) load(listOf(showId))
        val rootNode = nodes[showId]
        val root = rootNode?.anime ?: throw AniListException("That anime couldn't be found")
        val linked = rootNode.prequels.orEmpty() + rootNode.sequels.orEmpty()
        if (linked.any { nodes[it]?.prequels == null }) runCatching { preloadByTitle(root) }

        val members = LinkedHashSet<Int>().apply { add(showId) }
        val tried = HashSet<Int>().apply { add(showId) }
        var frontier = listOf(showId)
        while (frontier.isNotEmpty() && members.size < 60) {
            val unloaded = frontier.filter { nodes[it]?.prequels == null }
            if (unloaded.isNotEmpty()) load(unloaded)
            val candidates = frontier
                .flatMap { nodes[it]?.let { n -> n.prequels.orEmpty() + n.sequels.orEmpty() }.orEmpty() }
                .filter { tried.add(it) }
            if (candidates.isEmpty()) break
            val rootOf = rootsOf(candidates)
            frontier = candidates.filter { rootOf[it] == showId }
            members += frontier
        }
        return buildSeries(root, members.mapNotNull { nodes[it]?.anime }).also { seriesCache[showId] = it }
    }

    /** Most seasons share the show's name, so one search usually loads the whole chain at once. */
    private suspend fun preloadByTitle(root: Anime) {
        val term = (root.romaji ?: root.title).replace(Regex("\\(.*?\\)"), "").trim()
        if (term.length < 2) return
        val query = "query (\$s: String) { Page(perPage: 50) { media(search: \$s, type: ANIME) { $CARD $LINKS } } }"
        post(query, buildJsonObject { put("s", term) }).page?.media?.forEach { register(it, full = false) }
    }

    // ---- grouping seasons into shows ----

    /**
     * Maps each entry to its show. Loads whatever links are missing, a batch at a time.
     * If AniList can't be reached partway, entries stand for themselves rather than failing the page.
     */
    suspend fun rootsOf(ids: Collection<Int>): Map<Int, Int> {
        var rounds = 0
        while (true) {
            val missing = HashSet<Int>()
            val out = HashMap<Int, Int>()
            for (id in ids) rootOrNull(id, missing)?.let { out[id] = it }
            if (missing.isEmpty()) {
                withContext(Dispatchers.IO) { saveRoots() }
                return out
            }
            if (++rounds > 12) {
                ids.forEach { out.putIfAbsent(it, it) }
                return out
            }
            load(missing)
        }
    }

    private fun dateKey(id: Int): Long = (nodes[id]?.anime?.date ?: NO_DATE).toLong() * 10_000_000L + id

    /** Follows prequel links back through the story. Null (and an id in [missing]) if a link isn't loaded yet. */
    private fun chainOf(id: Int, missing: MutableSet<Int>): List<Int>? {
        val chain = ArrayList<Int>()
        val seen = HashSet<Int>()
        var cur = id
        while (seen.add(cur) && chain.size < 40) {
            val prequels = nodes[cur]?.prequels
            if (prequels == null) {
                missing += cur
                return null
            }
            chain += cur
            cur = prequels.minByOrNull(::dateKey) ?: break
        }
        return chain
    }

    private fun rootOrNull(id: Int, missing: MutableSet<Int>, depth: Int = 0): Int? {
        roots[id]?.let { return it }
        val chain = chainOf(id, missing) ?: return null
        // The show is the earliest-released series in the story so far. Going by release date keeps
        // a later prequel (a film, a spin-off season) from replacing the original as the face of the show.
        val series = chain.filter { nodes[it]?.anime?.isSeries == true }
        val root = if (series.isNotEmpty()) {
            series.minBy(::dateKey).also { roots[it] = it }
        } else {
            // A prequel film or pilot with no series before it: file it under the series it leads into.
            val next = chain.flatMap { nodes[it]?.sequels.orEmpty() }
                .filter { nodes[it]?.anime?.isSeries == true }
                .minByOrNull(::dateKey)
            if (next != null && depth < 2) rootOrNull(next, missing, depth + 1) ?: return null
            else chain.minBy(::dateKey)
        }
        roots[id] = root
        return root
    }

    /** Makes sure each id has at least card details loaded. */
    private suspend fun ensureCards(ids: Collection<Int>) {
        val missing = ids.filter { nodes[it]?.anime == null }.distinct()
        if (missing.isNotEmpty()) load(missing)
    }

    /** Loads full details and links for entries, 50 per request. */
    private suspend fun load(ids: Collection<Int>) {
        val query = "query (\$ids: [Int]) { Page(perPage: 50) { media(id_in: \$ids, type: ANIME) { $FULL $DEEP_LINKS } } }"
        ids.distinct().chunked(50).forEach { chunk ->
            val vars = buildJsonObject { putJsonArray("ids") { chunk.forEach { add(it) } } }
            post(query, vars).page?.media?.forEach { register(it, full = true) }
            // Anything AniList didn't return (removed, or not an anime) stands alone.
            chunk.forEach { id ->
                val n = nodes.getOrPut(id) { Node() }
                if (n.prequels == null) {
                    n.prequels = emptyList()
                    n.sequels = emptyList()
                }
            }
        }
    }

    private fun register(m: MediaDto, full: Boolean) {
        val node = nodes.getOrPut(m.id) { Node() }
        val old = node.anime
        val hasTitle = m.title.romaji != null || m.title.english != null
        if (old == null || (!old.full && (full || (old.title.isEmpty() && hasTitle)))) {
            node.anime = m.toAnime(full)
        }
        val edges = m.relations?.edges ?: return
        val prequels = ArrayList<Int>()
        val sequels = ArrayList<Int>()
        for (e in edges) {
            val n = e.node ?: continue
            if (n.type != "ANIME" || n.format == "MUSIC") continue
            val list = when (e.relationType) {
                "PREQUEL" -> prequels
                "SEQUEL" -> sequels
                else -> continue
            }
            register(n, full = false)
            list += n.id
        }
        node.prequels = prequels
        node.sequels = sequels
    }

    // ---- someone's own AniList list ----

    /**
     * A person's public anime list, by their AniList username. No sign-in is involved, so a list
     * that's set to private can't be read.
     */
    suspend fun userList(name: String): UserList {
        val query = "query (\$name: String, \$chunk: Int) {" +
            " MediaListCollection(userName: \$name, type: ANIME, chunk: \$chunk, perChunk: 500) {" +
            " hasNextChunk user { name } lists { entries {" +
            " mediaId status progress repeat score(format: POINT_100) updatedAt media { isAdult } } } } }"
        val rows = HashMap<Int, ListRow>()
        var canonical = name
        var chunk = 1
        while (chunk <= 20) {
            val resp = ask(query, buildJsonObject { put("name", name); put("chunk", chunk) })
            val list = resp.data?.list
            if (list == null) {
                val err = resp.errors?.firstOrNull()
                throw AniListException(
                    when {
                        err?.status == 404 -> "There's no AniList account called \"$name\"."
                        err?.message?.contains("rivate") == true ->
                            "That AniList list is private. Make it public in AniList's settings, then try again."
                        else -> "AniList couldn't share that list. Try again in a minute."
                    },
                )
            }
            list.user?.name?.takeIf { it.isNotBlank() }?.let { canonical = it }
            // A show can sit in several of a person's lists at once; it's still one row.
            for (e in list.lists.flatMap { it.entries }) {
                val row = ListRow(
                    e.mediaId, e.status ?: continue, e.progress ?: 0, e.score?.toInt() ?: 0,
                    e.repeat ?: 0, e.updatedAt ?: 0, e.media?.isAdult == true,
                )
                val old = rows[row.mediaId]
                if (old == null || row.updatedAt > old.updatedAt) rows[row.mediaId] = row
            }
            if (!list.hasNextChunk) break
            chunk++
        }
        return UserList(canonical, rows.values.toList())
    }

    /**
     * Loads the season links around a batch of entries, 50 a request, until every chain is known.
     * Sorting a long list into shows this way takes a handful of requests rather than a couple per show.
     */
    suspend fun warm(ids: Collection<Int>) {
        repeat(8) {
            val reached = HashSet<Int>()
            val missing = LinkedHashSet<Int>()
            val queue = ArrayDeque(ids)
            while (queue.isNotEmpty() && reached.size < 5000) {
                val id = queue.removeFirst()
                if (!reached.add(id)) continue
                val node = nodes[id]
                val prequels = node?.prequels
                if (prequels == null) {
                    missing += id
                    continue
                }
                queue += prequels
                queue += node.sequels.orEmpty()
            }
            if (missing.isEmpty()) return
            load(missing)
        }
    }

    /**
     * The few facts achievements need about each show (genres, format, year and so on), up to 50
     * shows a request. Ids AniList doesn't know come back empty, so they aren't asked for again.
     */
    suspend fun details(ids: Collection<Int>): Map<Int, ShowMeta> {
        val query = "query (\$ids: [Int]) { Page(perPage: 50) { media(id_in: \$ids, type: ANIME) {" +
            " id format seasonYear startDate { year month day } genres popularity averageScore" +
            " studios(isMain: true) { nodes { name } } } } }"
        val vars = buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }
        val out = HashMap<Int, ShowMeta>()
        post(query, vars).page?.media?.forEach { m ->
            out[m.id] = ShowMeta(
                m.genres.orEmpty(), m.format, m.seasonYear ?: m.startDate?.year,
                m.popularity, m.averageScore, m.studios?.nodes?.firstOrNull()?.name,
            )
        }
        ids.forEach { out.putIfAbsent(it, ShowMeta()) }
        return out
    }

    // ---- banner art ----

    /** Show id to its wide banner image; an empty string means the show has none. Kept across runs. */
    private val bannerUrls = ConcurrentHashMap<Int, String>()
    private var bannerFile: File? = null

    /** The wide banner image for each of these shows that has one. One request for any not yet known. */
    suspend fun banners(ids: Collection<Int>): Map<Int, String> {
        for (id in ids) {
            if (!bannerUrls.containsKey(id)) nodes[id]?.anime?.takeIf { it.full }?.let { bannerUrls[id] = it.banner.orEmpty() }
        }
        val missing = ids.filter { !bannerUrls.containsKey(it) }.distinct().take(50)
        if (missing.isNotEmpty()) {
            val query = "query (\$ids: [Int]) { Page(perPage: 50) { media(id_in: \$ids, type: ANIME) { id bannerImage } } }"
            val vars = buildJsonObject { putJsonArray("ids") { missing.forEach { add(it) } } }
            val found = post(query, vars).page?.media.orEmpty()
            found.forEach { bannerUrls[it.id] = it.bannerImage.orEmpty() }
            missing.forEach { bannerUrls.putIfAbsent(it, "") }
            withContext(Dispatchers.IO) {
                runCatching { bannerFile?.writeText(bannerUrls.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
            }
        }
        return ids.mapNotNull { id -> bannerUrls[id]?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
    }

    // ---- transport ----

    private suspend fun post(query: String, vars: JsonObject): DataDto {
        val resp = ask(query, vars)
        return resp.data ?: throw AniListException(resp.errors?.firstOrNull()?.message ?: "Empty response")
    }

    private suspend fun ask(query: String, vars: JsonObject): RespDto = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("query", query)
            put("variables", vars)
        }.toString()
        repeat(3) { attempt ->
            val req = Request.Builder().url(URL)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json").build()
            val resp = try {
                http.newCall(req).execute()
            } catch (e: Exception) {
                throw AniListException("You're offline, or AniList can't be reached")
            }
            val wait = resp.use { r ->
                if (r.code == 429) {
                    r.header("Retry-After")?.toLongOrNull() ?: 30L
                } else {
                    val text = r.body?.string().orEmpty()
                    return@withContext runCatching { json.decodeFromString<RespDto>(text) }.getOrNull()
                        ?: throw AniListException("AniList didn't respond properly (${r.code})")
                }
            }
            // AniList allows a set number of requests a minute. Short waits are worth sitting out.
            if (attempt == 2 || wait > 65) throw AniListException("AniList is busy. Try again in a minute.")
            delay((wait + 1) * 1000)
        }
        throw AniListException("AniList is busy. Try again in a minute.")
    }

    private fun MediaDto.toAnime(full: Boolean): Anime {
        val y = startDate?.year
        return Anime(
            id = id,
            title = title.english ?: title.romaji ?: "",
            romaji = title.romaji,
            nativeTitle = title.native,
            cover = coverImage?.extraLarge ?: coverImage?.large,
            banner = bannerImage,
            color = coverImage?.color,
            genres = genres.orEmpty(),
            score = averageScore,
            popularity = popularity,
            episodes = episodes,
            year = seasonYear ?: y,
            format = format,
            status = status,
            studio = studios?.nodes?.firstOrNull()?.name,
            description = description?.let(::cleanDescription),
            date = if (y == null) NO_DATE else y * 10000 + (startDate?.month ?: 12) * 100 + (startDate?.day ?: 28),
            aired = nextAiringEpisode?.episode?.let { (it - 1).coerceAtLeast(0) },
            full = full,
        )
    }

    /** AniList synopses are HTML with <br> and <i>; turn them into clean paragraphs. */
    private fun cleanDescription(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}

@Serializable private data class RespDto(val data: DataDto? = null, val errors: List<ErrDto>? = null)
@Serializable private data class ErrDto(val message: String = "", val status: Int? = null)
@Serializable private data class DataDto(
    @SerialName("Page") val page: PageDto? = null,
    @SerialName("Media") val media: MediaDto? = null,
    @SerialName("MediaListCollection") val list: ListCollectionDto? = null,
)
@Serializable private data class ListCollectionDto(
    val hasNextChunk: Boolean = false,
    val user: ListUserDto? = null,
    val lists: List<ListDto> = emptyList(),
)
@Serializable private data class ListUserDto(val name: String = "")
@Serializable private data class ListDto(val entries: List<ListEntryDto> = emptyList())
@Serializable private data class ListEntryDto(
    val mediaId: Int,
    val status: String? = null,
    val progress: Int? = null,
    val repeat: Int? = null,
    val score: Double? = null,
    val updatedAt: Long? = null,
    val media: ListMediaDto? = null,
)
@Serializable private data class ListMediaDto(val isAdult: Boolean? = null)
@Serializable private data class PageDto(val pageInfo: PageInfoDto = PageInfoDto(), val media: List<MediaDto> = emptyList())
@Serializable private data class PageInfoDto(val hasNextPage: Boolean = false)
@Serializable private data class TitleDto(val romaji: String? = null, val english: String? = null, val native: String? = null)
@Serializable private data class CoverDto(val extraLarge: String? = null, val large: String? = null, val color: String? = null)
@Serializable private data class StudioNodes(val nodes: List<StudioDto> = emptyList())
@Serializable private data class StudioDto(val name: String = "")
@Serializable private data class DateDto(val year: Int? = null, val month: Int? = null, val day: Int? = null)
@Serializable private data class AiringDto(val episode: Int? = null)
@Serializable private data class RelationsDto(val edges: List<EdgeDto> = emptyList())
@Serializable private data class EdgeDto(val relationType: String? = null, val node: MediaDto? = null)
@Serializable private data class MediaDto(
    val id: Int,
    val type: String? = null,
    val title: TitleDto = TitleDto(),
    val coverImage: CoverDto? = null,
    val bannerImage: String? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val episodes: Int? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val studios: StudioNodes? = null,
    val description: String? = null,
    val startDate: DateDto? = null,
    val nextAiringEpisode: AiringDto? = null,
    val relations: RelationsDto? = null,
)
