package com.kumo.anime.data

import android.text.Html
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class Sort(val api: String, val label: String) {
    POPULAR("POPULARITY_DESC", "Popular"),
    TRENDING("TRENDING_DESC", "Trending"),
    TOP("SCORE_DESC", "Top rated"),
}

/** AniList's genre list, minus the adult-only one. */
val GENRES = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mahou Shoujo", "Mecha",
    "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports",
    "Supernatural", "Thriller", "Ecchi",
)

data class Anime(
    val id: Int,
    val title: String,
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
) {
    val meta: String
        get() = listOfNotNull(
            format?.let(::prettyFormat),
            year?.toString(),
            episodes?.let { if (it == 1) "1 ep" else "$it eps" },
        ).joinToString(" · ")
}

private fun prettyFormat(f: String) = when (f) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> f.lowercase().replaceFirstChar { it.uppercase() }
}

data class AnimePage(val items: List<Anime>, val hasNext: Boolean)

class AniListException(message: String) : Exception(message)

/** Thin client for the public AniList GraphQL API (no key needed). */
object AniList {
    private const val URL = "https://graphql.anilist.co"
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<Int, Anime>()

    fun cached(id: Int): Anime? = cache[id]

    private const val FIELDS = """
        id
        title { romaji english native }
        coverImage { extraLarge large color }
        bannerImage
        genres
        averageScore
        popularity
        episodes
        seasonYear
        format
        status
        studios(isMain: true) { nodes { name } }
        description(asHtml: true)
    """

    suspend fun browse(page: Int, sort: Sort, genre: String?, search: String?): AnimePage {
        val query = "query (\$page: Int, \$sort: [MediaSort], \$genre: String, \$search: String) {" +
            " Page(page: \$page, perPage: 30) { pageInfo { hasNextPage }" +
            " media(type: ANIME, isAdult: false, sort: \$sort, genre: \$genre, search: \$search) { $FIELDS } } }"
        val vars = buildJsonObject {
            put("page", page)
            putJsonArray("sort") { add(if (search.isNullOrBlank()) sort.api else "SEARCH_MATCH") }
            if (genre != null) put("genre", genre)
            if (!search.isNullOrBlank()) put("search", search.trim())
        }
        val p = post(query, vars).page ?: throw AniListException("AniList sent an empty page")
        val items = p.media.map { it.toAnime().also { a -> cache[a.id] = a } }
        return AnimePage(items, p.pageInfo.hasNextPage)
    }

    suspend fun get(id: Int): Anime {
        cache[id]?.let { return it }
        val query = "query (\$id: Int) { Media(id: \$id, type: ANIME) { $FIELDS } }"
        val m = post(query, buildJsonObject { put("id", id) }).media
            ?: throw AniListException("That anime couldn't be found")
        return m.toAnime().also { cache[it.id] = it }
    }

    private suspend fun post(query: String, vars: JsonObject): DataDto = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("query", query)
            put("variables", vars)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(URL).post(body).header("Accept", "application/json").build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw AniListException("You're offline, or AniList can't be reached")
        }
        resp.use { r ->
            if (r.code == 429) throw AniListException("AniList is busy. Try again in a minute.")
            val text = r.body?.string().orEmpty()
            val parsed = runCatching { json.decodeFromString<RespDto>(text) }.getOrNull()
                ?: throw AniListException("AniList didn't respond properly (${r.code})")
            parsed.data ?: throw AniListException(parsed.errors?.firstOrNull()?.message ?: "Empty response")
        }
    }

    private fun MediaDto.toAnime() = Anime(
        id = id,
        title = title.english ?: title.romaji ?: "Untitled",
        nativeTitle = title.native,
        cover = coverImage?.extraLarge ?: coverImage?.large,
        banner = bannerImage,
        color = coverImage?.color,
        genres = genres,
        score = averageScore,
        popularity = popularity,
        episodes = episodes,
        year = seasonYear,
        format = format,
        status = status,
        studio = studios?.nodes?.firstOrNull()?.name,
        description = description?.let(::cleanDescription),
    )

    /** AniList synopses are HTML with <br> and <i>; turn them into clean paragraphs. */
    private fun cleanDescription(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}

@Serializable private data class RespDto(val data: DataDto? = null, val errors: List<ErrDto>? = null)
@Serializable private data class ErrDto(val message: String = "")
@Serializable private data class DataDto(
    @SerialName("Page") val page: PageDto? = null,
    @SerialName("Media") val media: MediaDto? = null,
)
@Serializable private data class PageDto(val pageInfo: PageInfoDto = PageInfoDto(), val media: List<MediaDto> = emptyList())
@Serializable private data class PageInfoDto(val hasNextPage: Boolean = false)
@Serializable private data class TitleDto(val romaji: String? = null, val english: String? = null, val native: String? = null)
@Serializable private data class CoverDto(val extraLarge: String? = null, val large: String? = null, val color: String? = null)
@Serializable private data class StudioNodes(val nodes: List<StudioDto> = emptyList())
@Serializable private data class StudioDto(val name: String = "")
@Serializable private data class MediaDto(
    val id: Int,
    val title: TitleDto = TitleDto(),
    val coverImage: CoverDto? = null,
    val bannerImage: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val episodes: Int? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val studios: StudioNodes? = null,
    val description: String? = null,
)
