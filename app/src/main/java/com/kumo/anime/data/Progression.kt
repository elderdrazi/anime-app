package com.kumo.anime.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar

/**
 * What's known about the shows on your list, for the achievements that depend on genre, age and
 * so on. Fetched from AniList once per show and kept in a file on the phone.
 */
object ShowInfo {
    private const val TAG = "ShowInfo"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = CompletableDeferred<Unit>()
    private val busy = Mutex()
    private var file: File? = null

    private val _all = MutableStateFlow<Map<Int, ShowMeta>>(emptyMap())
    val all: StateFlow<Map<Int, ShowMeta>> = _all.asStateFlow()

    /** True while details are being fetched, so nothing is announced off half a picture. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "show-info.json")
        file = f
        scope.launch {
            _all.value = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString<Map<Int, ShowMeta>>(f.readText()) }.getOrNull() ?: emptyMap()
            }
            ready.complete(Unit)
        }
    }

    /** Fetches details for any of these shows that aren't known yet. Quietly gives up when offline. */
    suspend fun ensure(ids: Collection<Int>) {
        _loading.value = true
        try {
            ready.await()
            busy.withLock {
                val missing = ids.filter { it !in _all.value }.distinct()
                for (chunk in missing.chunked(50)) {
                    val found = runCatching { AniList.details(chunk) }
                        .onFailure { Log.w(TAG, "details failed", it) }.getOrNull() ?: break
                    val merged = _all.value + found
                    _all.value = merged
                    withContext(Dispatchers.IO) { runCatching { file?.writeText(json.encodeToString(merged)) } }
                }
            }
        } finally {
            _loading.value = false
        }
    }
}

/**
 * Keeps your level and achievements up to date as your list, friends and habits change, saves new
 * unlocks to your profile so friends can see them, and reports them for the app to announce.
 */
@OptIn(FlowPreview::class)
object Progression {
    /** Something worth telling the person: achievements just unlocked, and the new level on a level-up. */
    class News(val unlocked: List<Achievement>, val level: Int?)

    private const val TAG = "Progression"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _standing = MutableStateFlow<Standing?>(null)
    /** Null until your list and saved progress have loaded. */
    val standing: StateFlow<Standing?> = _standing.asStateFlow()

    private val _news = MutableSharedFlow<News>(extraBufferCapacity = 8)
    val news: SharedFlow<News> = _news.asSharedFlow()

    private class Lists(val ready: Boolean, val entries: List<Entry>, val friends: Int, val together: Int, val meta: Map<Int, ShowMeta>)
    private class Person(val uid: String?, val record: Record, val email: Boolean, val link: AniListLink?, val loading: Boolean)

    fun start(social: Social) {
        if (!social.enabled) return
        scope.launch {
            social.myEntries.collectLatest { list ->
                ShowInfo.ensure(list.filter { it.status != WatchStatus.PLANNED }.map { it.animeId })
            }
        }
        scope.launch {
            val lists = combine(social.listReady, social.myEntries, social.friends, social.suggestions, ShowInfo.all) { ready, e, f, s, m ->
                Lists(ready, e, f.size, s.count { it.status == "accepted" }, m)
            }
            val person = combine(social.session, social.record, social.email, AniListSync.link, ShowInfo.loading) { s, r, e, l, busy ->
                Person((s as? Session.Ready)?.me?.uid, r, e != null, l, busy)
            }
            // The short wait lets a burst of changes (a list arriving, an import) settle into one result.
            combine(lists, person) { a, b -> a to b }.debounce(700).collectLatest { (a, b) ->
                runCatching { settle(social, a, b) }.onFailure { Log.w(TAG, "settle failed", it) }
            }
        }
    }

    private suspend fun settle(social: Social, lists: Lists, person: Person) {
        val uid = person.uid
        val record = person.record
        if (uid == null || !record.loaded || !lists.ready) {
            if (uid == null) _standing.value = null
            return
        }
        val stats = statsOf(
            lists.entries, lists.meta, lists.friends, lists.together, record.tracked,
            photo = record.photo, email = person.email, anilist = person.link?.uid == uid,
            year = Calendar.getInstance().get(Calendar.YEAR),
        )
        val now = evaluate(stats, record.unlocked.keys)
        _standing.value = now
        // Hold off saving and announcing while show details are still arriving.
        if (person.loading) return
        val fresh = now.unlocked - record.unlocked.keys
        if (fresh.isEmpty() && record.xp == now.xp && record.level == now.level) return
        social.saveStanding(fresh, now.xp, now.level)
        // Nothing saved before means this is the first run on an existing list: that's catching up,
        // not a level-up.
        val levelUp = now.level.takeIf { record.level in 1 until it }
        if (fresh.isNotEmpty() || levelUp != null) {
            _news.tryEmit(News(fresh.mapNotNull { ACHIEVEMENTS_BY_ID[it] }.sortedByDescending { it.tier }, levelUp))
        }
    }
}
