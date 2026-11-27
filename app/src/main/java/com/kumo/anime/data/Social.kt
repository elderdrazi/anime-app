package com.kumo.anime.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.kumo.anime.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class WatchStatus(val key: String, val label: String) {
    PLANNED("planned", "Want to watch"),
    WATCHING("watching", "Watching"),
    WATCHED("watched", "Watched");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

data class Profile(
    val uid: String,
    val username: String,
    val createdAt: Long = 0,
    val xp: Int = 0,
    val level: Int = 1,
    /** Achievement id to when it was unlocked. */
    val unlocked: Map<String, Long> = emptyMap(),
)

/** How a person appears wherever their avatar is drawn: their picture, if any, and the border around it. */
data class Face(val photo: ImageBitmap? = null, val frame: Frame = Frame.NONE)

/** What's saved on your own profile about your progress. [loaded] is false until it has arrived. */
data class Record(
    val loaded: Boolean = false,
    val tracked: Tracked = Tracked(),
    val unlocked: Map<String, Long> = emptyMap(),
    val xp: Int = 0,
    /** 0 when no level has ever been saved. */
    val level: Int = 0,
    val frame: Frame = Frame.NONE,
    val photo: Boolean = false,
)

data class Entry(
    val animeId: Int,
    val title: String,
    val cover: String?,
    val status: WatchStatus,
    val progress: Int,
    val total: Int?,
    val updatedAt: Long,
    /** Where in the show this is, like "Season 2 · Ep 5". Null for single-season shows. */
    val label: String? = null,
    /** True once the entry covers a whole show. Older versions saved one entry per season. */
    val whole: Boolean = false,
    /** Your own score for the show, 1 to 100. */
    val rating: Int? = null,
    /** Episodes seen in each season, film or special, by its id. Null on entries saved before 1.3. */
    val seen: Map<Int, Int>? = null,
)

/** A person's best-rated shows, highest first. */
fun topRated(entries: List<Entry>, count: Int = 10): List<Entry> =
    entries.filter { it.rating != null }
        .sortedWith(compareByDescending<Entry> { it.rating }.thenByDescending { it.updatedAt })
        .take(count)

data class Friend(val uid: String, val username: String)

/** What your friends make of one show: the average of their ratings, and who rated it, keenest first. */
data class FriendPick(
    val animeId: Int,
    val title: String,
    val cover: String?,
    val average: Int,
    val count: Int,
    val names: List<String>,
)

/** Gathers friends' rated entries into one [FriendPick] per show. */
fun friendPicks(rated: List<Pair<Friend, Entry>>): Map<Int, FriendPick> =
    rated.filter { it.second.rating != null }.groupBy { it.second.animeId }.mapValues { (id, all) ->
        val each = all.distinctBy { it.first.uid }.sortedByDescending { it.second.rating }
        val shown = each.first().second
        FriendPick(
            id, shown.title, shown.cover,
            average = (each.sumOf { it.second.rating ?: 0 } / each.size.toDouble()).roundToInt(),
            count = each.size,
            names = each.map { it.first.username },
        )
    }

/** A show counts as a friends' favourite once their ratings average this or more. */
const val FAVORITE_FROM = 70

/**
 * Friends' favourites, ordered for you: shows you haven't watched come first, then ones you're
 * watching, then ones you've finished. Within each, the best loved lead, and a show several
 * friends agree on edges out one a single friend rated the same.
 */
fun friendFavorites(picks: Collection<FriendPick>, mine: List<Entry>): List<FriendPick> {
    val status = mine.associate { it.animeId to it.status }
    fun seen(p: FriendPick) = when (status[p.animeId]) {
        WatchStatus.WATCHED -> 2
        WatchStatus.WATCHING -> 1
        else -> 0
    }
    return picks.filter { it.average >= FAVORITE_FROM }.sortedWith(
        compareBy<FriendPick> { seen(it) }
            .thenByDescending { it.average + 4 * (it.count - 1) }
            .thenByDescending { it.count }
            .thenBy { it.title },
    )
}

/** A friend's entry for one anime, for comparison on the detail page. */
data class FriendEntry(val friend: Friend, val entry: Entry)

object NoticeType {
    const val FRIEND_REQUEST = "friend_request"
    const val FRIEND_ACCEPTED = "friend_accepted"
    const val SUGGESTION = "suggestion"
    const val SUGGESTION_REPLY = "suggestion_reply"
    const val CAUGHT_UP = "caught_up"
    const val RECOMMENDATION = "recommendation"
}

data class Notice(
    val id: String,
    val type: String,
    val fromUid: String,
    val fromName: String,
    val animeId: Int?,
    val title: String?,
    val cover: String?,
    val message: String?,
    val suggestionId: String?,
    val accepted: Boolean?,
    val createdAt: Long,
    val read: Boolean,
    val handled: Boolean,
)

data class Suggestion(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val toUid: String,
    val toName: String,
    val animeId: Int,
    val title: String,
    val cover: String?,
    val status: String,
    val createdAt: Long,
) {
    fun partnerName(me: String) = if (fromUid == me) toName else fromName
    fun partnerUid(me: String) = if (fromUid == me) toUid else fromUid
}

sealed interface Session {
    data object Loading : Session
    data object Disabled : Session
    data object NeedsUsername : Session
    data class Ready(val me: Profile) : Session
    data class Failed(val message: String) : Session
}

class UsernameTakenException : Exception("That username's taken. Try another.")

/** A sign-in or email problem, with a message fit to show as is. */
class AccountException(message: String) : Exception(message)

/**
 * Everything social: account, lists, friends, suggestions and the inbox, all on Firestore.
 * Free-tier friendly: no Cloud Functions, and every query is single-field so no indexes are needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Social(private val app: Context) {
    val enabled = BuildConfig.HAS_FIREBASE
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _session = MutableStateFlow<Session>(if (enabled) Session.Loading else Session.Disabled)
    val session: StateFlow<Session> = _session.asStateFlow()

    val myUid: String? get() = (session.value as? Session.Ready)?.me?.uid
    val me: Profile? get() = (session.value as? Session.Ready)?.me

    private fun <T> whenReady(empty: T, block: (Profile) -> Flow<T>): StateFlow<T> =
        session.flatMapLatest { s -> if (s is Session.Ready) block(s.me) else flowOf(empty) }
            .stateIn(scope, SharingStarted.Eagerly, empty)

    private val _listReady = MutableStateFlow(false)
    /** False until your list has arrived from the database, so an empty list isn't mistaken for a real one. */
    val listReady: StateFlow<Boolean> = _listReady.asStateFlow()

    val myEntries: StateFlow<List<Entry>> = whenReady(emptyList()) { me ->
        _listReady.value = false
        entriesOf(me.uid).onEach { _listReady.value = true }
    }

    /** Your saved progress: counters, unlocked achievements, level and chosen border. */
    val record: StateFlow<Record> = whenReady(Record()) { me ->
        callbackFlow {
            val reg = userDoc(me.uid).addSnapshotListener { snap, err ->
                if (err != null) Log.w(TAG, "profile listener failed", err)
                if (snap != null && snap.exists()) trySend(toRecord(snap))
            }
            awaitClose { reg.remove() }
        }
    }
    val friends: StateFlow<List<Friend>> = whenReady(emptyList()) { me ->
        db.collection("users").document(me.uid).collection("friends").listen { d ->
            d.getString("username")?.let { Friend(d.id, it) }
        }
    }
    val notices: StateFlow<List<Notice>> = whenReady(emptyList()) { me ->
        db.collection("users").document(me.uid).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(60)
            .listen(::toNotice)
    }
    val suggestions: StateFlow<List<Suggestion>> = whenReady(emptyList()) { me ->
        db.collection("suggestions").whereArrayContains("participants", me.uid).listen(::toSuggestion)
    }

    private val _friendRatings = MutableStateFlow<Map<Int, FriendPick>>(emptyMap())
    /** Every show at least one friend has rated, by show id. Read when the app opens and on request. */
    val friendRatings: StateFlow<Map<Int, FriendPick>> = _friendRatings.asStateFlow()
    private var ratingsJob: Job? = null

    /** Reads friends' ratings again, for a pull-to-refresh style update. */
    fun refreshFriendRatings() {
        if (!enabled) return
        ratingsJob?.cancel()
        ratingsJob = scope.launch { loadFriendRatings(friends.value) }
    }

    private suspend fun loadFriendRatings(list: List<Friend>) = coroutineScope {
        // One small query per friend: only the entries they've rated.
        val rated = list.take(40).map { f ->
            async {
                runCatching { entryDocs(f.uid).whereGreaterThan("rating", 0).get().await() }
                    .getOrNull()?.documents?.mapNotNull(::toEntry)?.map { f to it }.orEmpty()
            }
        }.awaitAll().flatten()
        _friendRatings.value = friendPicks(rated)
    }

    private var upgrading = false

    fun start() {
        if (!enabled) return
        if (!upgrading) {
            upgrading = true
            scope.launch { friends.collectLatest { loadFriendRatings(it) } }
            // Entries saved per season by version 1.0 are folded into whole-show entries.
            scope.launch {
                myEntries.collect { list ->
                    if (list.any { !it.whole }) runCatching { upgradeEntries(list) }.onFailure { Log.w(TAG, "upgrade failed", it) }
                }
            }
        }
        scope.launch {
            _session.value = Session.Loading
            _session.value = try {
                val user = auth.currentUser ?: auth.signInAnonymously().await().user
                    ?: error("Sign-in failed")
                // Guest accounts report an empty email rather than none.
                _email.value = user.email?.takeIf { it.isNotBlank() }
                val doc = db.collection("users").document(user.uid).get().await()
                val name = doc.getString("username")
                if (name == null) Session.NeedsUsername else Session.Ready(Profile(user.uid, name, doc.getLong("createdAt") ?: 0))
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
                Session.Failed("Couldn't connect. Check your connection and try again.")
            }
        }
    }

    // ---- email ----
    // Accounts start out tied to the phone. Adding an email and password lets the same account be
    // opened on another phone or after a reinstall. The email stays in Firebase sign-in; it is never
    // written to the database, so friends can't see it.

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email.asStateFlow()

    /** Attaches an email and password to the current account, keeping its list and friends. */
    suspend fun addEmail(email: String, password: String) {
        val user = auth.currentUser ?: throw AccountException("You're not signed in.")
        try {
            user.linkWithCredential(EmailAuthProvider.getCredential(email.trim(), password)).await()
        } catch (e: Exception) {
            throw AccountException(accountMessage(e))
        }
        _email.value = auth.currentUser?.email?.takeIf { it.isNotBlank() }
    }

    /** Opens an existing account on this phone. The fresh guest account it replaces is left empty. */
    suspend fun signIn(email: String, password: String) {
        try {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
        } catch (e: Exception) {
            throw AccountException(accountMessage(e))
        }
        start()
    }

    suspend fun sendPasswordReset(email: String) {
        try {
            auth.sendPasswordResetEmail(email.trim()).await()
        } catch (e: Exception) {
            throw AccountException(accountMessage(e))
        }
    }

    /** Only offered once an email is attached, since a guest account can't be signed back into. */
    fun signOut() {
        auth.signOut()
        _email.value = null
        start()
    }

    private fun accountMessage(e: Exception): String = when {
        e is FirebaseAuthUserCollisionException -> "That email already has an account. Sign in with it instead."
        e is FirebaseAuthWeakPasswordException -> "Use a password of at least 6 characters."
        e is FirebaseAuthInvalidUserException -> "No account uses that email."
        e is FirebaseAuthInvalidCredentialsException -> "That email or password isn't right."
        (e as? FirebaseAuthException)?.errorCode == "ERROR_OPERATION_NOT_ALLOWED" -> "Email sign-in isn't switched on for this app yet."
        e is FirebaseNetworkException -> "Couldn't connect. Check your connection and try again."
        else -> "That didn't work. Try again."
    }

    suspend fun claimUsername(raw: String) {
        val name = raw.trim().removePrefix("@")
        require(Regex("^[A-Za-z0-9_]{3,20}$").matches(name)) { "Use 3 to 20 letters, numbers or underscores." }
        val uid = auth.currentUser?.uid ?: error("Not signed in")
        val nameRef = db.collection("usernames").document(name.lowercase())
        try {
            db.runTransaction { tx ->
                if (tx.get(nameRef).exists()) throw UsernameTakenException()
                tx.set(nameRef, mapOf("uid" to uid))
                tx.set(
                    db.collection("users").document(uid),
                    mapOf("username" to name, "usernameLower" to name.lowercase(), "createdAt" to now()),
                )
            }.await()
        } catch (e: Exception) {
            if (e is UsernameTakenException || e.cause is UsernameTakenException) throw UsernameTakenException()
            throw e
        }
        _session.value = Session.Ready(Profile(uid, name, now()))
    }

    // ---- profile pictures ----
    // A picture is a small JPEG kept in the person's own profile document, so it needs no separate
    // file storage. Each one is fetched once per run of the app and then kept in memory.

    private val faces = HashMap<String, MutableStateFlow<Face>>()
    private val noFace = MutableStateFlow(Face())

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    /** A person's picture and border. Plain until it loads, and for people who haven't set either. */
    fun faceOf(uid: String): StateFlow<Face> {
        if (!enabled || uid.isEmpty()) return noFace
        return faces.getOrPut(uid) {
            MutableStateFlow(Face()).also { flow -> scope.launch { loadFace(uid, flow) } }
        }
    }

    private suspend fun loadFace(uid: String, into: MutableStateFlow<Face>) {
        var shown: Long? = null
        // The copy saved on the phone shows at once; the server's replaces it if anything changed.
        for (source in listOf(Source.CACHE, Source.SERVER)) {
            val doc = runCatching { userDoc(uid).get(source).await() }.getOrNull()?.takeIf { it.exists() } ?: continue
            val frame = Frame.of(doc.getString("frame"))
            val stamp = doc.getLong("photoAt") ?: 0
            if (source == Source.SERVER && stamp == shown) {
                into.value = into.value.copy(frame = frame)
                continue
            }
            val bytes = doc.getBlob("photo")?.toBytes()
            into.value = Face(bytes?.let { withContext(Dispatchers.Default) { decodePhoto(it) } }, frame)
            shown = stamp
        }
    }

    /** Picks the border around your picture. Which ones are unlocked is the caller's business. */
    suspend fun setFrame(frame: Frame) {
        val uid = myUid ?: throw AccountException("You're not signed in.")
        userDoc(uid).update("frame", frame.key)
        faces.getOrPut(uid) { MutableStateFlow(Face()) }.let { it.value = it.value.copy(frame = frame) }
    }

    // ---- level and achievements ----

    /** Saves newly unlocked achievements, stamped now, along with the XP and level they add up to. */
    suspend fun saveStanding(fresh: Collection<String>, xp: Int, level: Int) {
        val uid = myUid ?: return
        val change = HashMap<String, Any>()
        val at = now()
        fresh.forEach { change["ach.$it"] = at }
        change["xp"] = xp
        change["level"] = level
        userDoc(uid).update(change).await()
    }

    /** The counter changes for logging [episodes] more episodes right now: streak, best day, late nights. */
    private fun watchedNow(episodes: Int): Map<String, Any> {
        val cal = Calendar.getInstance()
        fun day(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        val today = day(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        cal.add(Calendar.DAY_OF_MONTH, -1)
        val t = record.value.tracked.logged(episodes, today, day(cal), hour)
        return mapOf(
            "stats.lastDay" to t.lastDay, "stats.streak" to t.streak, "stats.bestStreak" to t.bestStreak,
            "stats.dayEps" to t.dayEps, "stats.bestDay" to t.bestDay, "stats.night" to t.night,
        )
    }

    /** Sets your profile picture from a small square JPEG, or removes it with null. */
    suspend fun setPhoto(jpeg: ByteArray?) {
        val uid = myUid ?: throw AccountException("You're not signed in.")
        val change: Map<String, Any> =
            if (jpeg == null) mapOf("photo" to FieldValue.delete(), "photoAt" to FieldValue.delete())
            else mapOf("photo" to Blob.fromBytes(jpeg), "photoAt" to now())
        try {
            withTimeout(15_000) { userDoc(uid).update(change).await() }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            throw AccountException("Couldn't save your picture. Check your connection and try again.")
        }
        faces.getOrPut(uid) { MutableStateFlow(Face()) }.let { it.value = it.value.copy(photo = jpeg?.let(::decodePhoto)) }
    }

    private fun decodePhoto(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

    // ---- lists and episode tracking ----

    fun entriesOf(uid: String): Flow<List<Entry>> =
        db.collection("users").document(uid).collection("entries").listen(::toEntry)

    private fun entryDocs(uid: String) = db.collection("users").document(uid).collection("entries")

    /** Your entry for a show. [series] may be null while its seasons are still loading. */
    fun entryFor(show: Anime, series: Series?): Entry? = merge(show, series, myEntries.value)

    /**
     * One entry for a whole show out of whatever is saved for it. Version 1.0 saved an entry per
     * season, so those are converted into a single running episode count across the show.
     */
    fun merge(show: Anime, series: Series?, all: List<Entry>): Entry? {
        val ids = series?.memberIds ?: setOf(show.id)
        val found = all.filter { it.animeId in ids }
        if (found.isEmpty()) return null
        val own = found.firstOrNull { it.animeId == show.id }
        if (series == null || (found.size == 1 && own?.whole == true)) return own
        val seen = HashMap<Int, Int>()
        for (e in found) {
            if (e.whole && e.animeId == show.id) {
                series.seenOf(e.seen, e.progress).forEach { (k, v) -> seen[k] = maxOf(seen[k] ?: 0, v) }
            } else {
                // A 1.0 entry covers just its own season.
                val i = series.watchable.indexOfFirst { it.id == e.animeId }
                if (i < 0 || e.status == WatchStatus.PLANNED) continue
                val len = series.lengthOf(i)
                val v = if (e.status == WatchStatus.WATCHED) len ?: e.progress else len?.let { e.progress.coerceAtMost(it) } ?: e.progress
                if (v > 0) seen[e.animeId] = maxOf(seen[e.animeId] ?: 0, v)
            }
        }
        val status = when {
            found.all { it.status == WatchStatus.PLANNED } -> WatchStatus.PLANNED
            series.isComplete(seen) -> WatchStatus.WATCHED
            else -> WatchStatus.WATCHING
        }
        return Entry(
            show.id, show.title, show.cover, status, series.count(seen), series.total,
            found.maxOf { it.updatedAt }, series.label(seen), whole = false,
            rating = own?.rating ?: found.mapNotNull { it.rating }.maxOrNull(),
            seen = seen,
        )
    }

    /** What you've seen of a show, part by part. Empty if it isn't started. */
    fun seenFor(show: Anime, series: Series): Map<Int, Int> {
        val e = entryFor(show, series) ?: return emptyMap()
        return if (e.status == WatchStatus.PLANNED) emptyMap() else series.seenOf(e.seen, e.progress)
    }

    /** Writes the single entry for a show and clears any per-season ones it replaces. */
    private fun save(
        batch: com.google.firebase.firestore.WriteBatch, uid: String, show: Anime, series: Series,
        status: WatchStatus, seen: Map<Int, Int>, updatedAt: Long = now(),
        rating: Int? = entryFor(show, series)?.rating,
        all: List<Entry> = myEntries.value,
    ) {
        val docs = entryDocs(uid)
        batch.set(
            docs.document(show.id.toString()),
            entryMap(
                show.id, show.title, show.cover, status, series.count(seen), series.total, series.label(seen),
                true, updatedAt, rating, seen.mapKeys { it.key.toString() },
            ),
        )
        all.filter { it.animeId in series.memberIds && it.animeId != show.id }
            .forEach { batch.delete(docs.document(it.animeId.toString())) }
    }

    /**
     * Brings rows from an AniList list into your list, show by show. [changed] are the AniList
     * entries to act on; [rows] is the whole list, so every season of a show is weighed together.
     * See [planImport] for the rules. Friends aren't notified: an import isn't something you just watched.
     */
    suspend fun importRows(rows: List<ListRow>, changed: Collection<Int>, onProgress: (Int, Int) -> Unit): ImportResult {
        val uid = myUid ?: throw AccountException("You're not signed in.")
        // Read from the database rather than the live list: just after launch the live list may not
        // have arrived yet, and merging into an empty one would overwrite what's saved.
        val mine = entryDocs(uid).get().await().documents.mapNotNull(::toEntry)
        val byId = rows.associateBy { it.mediaId }
        AniList.warm(changed)
        val shows = AniList.rootsOf(changed).values.distinct()
        var added = 0
        var updated = 0
        var leftOut = 0
        var batch = db.batch()
        var waiting = 0
        shows.forEachIndexed { i, showId ->
            onProgress(i, shows.size)
            val series = runCatching { AniList.series(showId) }.getOrNull()
            val show = AniList.cachedEntry(showId)
            if (series == null || show == null) {
                leftOut++
                return@forEachIndexed
            }
            val existing = merge(show, series, mine)
            val plan = planImport(series, series.parts.mapNotNull { byId[it.id] }, existing)
            if (plan == null) {
                if (existing == null) leftOut++
                return@forEachIndexed
            }
            save(batch, uid, show, series, plan.status, plan.seen, plan.updatedAt, plan.rating, mine)
            if (existing == null) added++ else updated++
            if (++waiting >= 40) {
                batch.commit().await()
                batch = db.batch()
                waiting = 0
            }
        }
        if (waiting > 0) batch.commit().await()
        onProgress(shows.size, shows.size)
        return ImportResult(added, updated, leftOut)
    }

    suspend fun setStatus(show: Anime, series: Series, status: WatchStatus?) {
        val uid = myUid ?: return
        val batch = db.batch()
        if (status == null) {
            myEntries.value.filter { it.animeId in series.memberIds }
                .forEach { batch.delete(entryDocs(uid).document(it.animeId.toString())) }
            batch.commit().await()
            return
        }
        val old = entryFor(show, series)
        val had = seenFor(show, series)
        val seen = when (status) {
            // Marking a show watched ticks everything; anything skipped can be unticked afterwards.
            WatchStatus.WATCHED -> series.all().ifEmpty { had }
            WatchStatus.PLANNED -> emptyMap()
            // Picking a finished show back up (a new season came out) carries on from where you were.
            WatchStatus.WATCHING ->
                if (old?.status == WatchStatus.WATCHING || (old?.status == WatchStatus.WATCHED && !series.isComplete(had))) had
                else emptyMap()
        }
        save(batch, uid, show, series, status, seen)
        batch.commit().await()
    }

    /** Your own score for a show on your list, 1 to 100, or null to clear it. Doesn't count as activity. */
    suspend fun setRating(show: Anime, series: Series, rating: Int?) {
        val uid = myUid ?: return
        val e = entryFor(show, series) ?: return
        val batch = db.batch()
        save(batch, uid, show, series, e.status, series.seenOf(e.seen, e.progress), e.updatedAt, rating?.coerceIn(1, 100))
        batch.commit().await()
    }

    /**
     * Saves what you've seen of a show, part by part. Finishing the newest released part marks the
     * show watched, and friends whose episode count you pass are told.
     */
    suspend fun setProgress(show: Anime, series: Series, seen: Map<Int, Int>, friendEntries: List<FriendEntry>) {
        val uid = myUid ?: return
        val me = me ?: return
        val ep = series.count(seen)
        val oldEp = entryFor(show, series)?.progress ?: 0
        val status = if (series.isComplete(seen)) WatchStatus.WATCHED else WatchStatus.WATCHING
        val batch = db.batch()
        save(batch, uid, show, series, status, seen)
        if (ep > oldEp) batch.update(userDoc(uid), watchedNow(ep - oldEp))
        friendEntries.filter { it.entry.status == WatchStatus.WATCHING && oldEp < it.entry.progress && ep >= it.entry.progress && it.entry.progress > 0 }
            .forEach { fe ->
                batch.set(
                    noticeRef(fe.friend.uid),
                    noticeMap(NoticeType.CAUGHT_UP, me, show.id, show.title, show.cover, series.describe(seen)),
                )
            }
        batch.commit().await()
    }

    /** Each friend's entry for a show, including ones still saved per season. */
    suspend fun friendEntries(show: Anime, series: Series?): List<FriendEntry> = coroutineScope {
        val ids = (series?.memberIds ?: setOf(show.id)).map { it.toString() }.take(30)
        friends.value.map { f ->
            async {
                runCatching {
                    entryDocs(f.uid).whereIn(FieldPath.documentId(), ids).get().await()
                }.getOrNull()?.documents?.mapNotNull(::toEntry)
                    ?.let { merge(show, series, it) }
                    ?.let { FriendEntry(f, it) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun upgradeEntries(entries: List<Entry>) {
        val uid = myUid ?: return
        val old = entries.filter { !it.whole }
        val rootOf = AniList.rootsOf(old.map { it.animeId })
        for (showId in rootOf.values.distinct()) {
            runCatching {
                val show = AniList.show(showId)
                val series = AniList.series(showId)
                val merged = merge(show, series, myEntries.value) ?: return@runCatching
                val batch = db.batch()
                save(batch, uid, show, series, merged.status, series.seenOf(merged.seen, merged.progress), merged.updatedAt)
                batch.commit().await()
            }.onFailure { Log.w(TAG, "couldn't upgrade $showId", it) }
        }
    }

    /** Puts a show from a suggestion or recommendation on "Want to watch", unless it's already tracked. */
    private suspend fun addPlanned(batch: com.google.firebase.firestore.WriteBatch, uid: String, n: Notice) {
        val id = n.animeId ?: return
        // Older versions send a season's id; file it under its show when that can be looked up.
        val showId = runCatching { AniList.rootsOf(listOf(id))[id] }.getOrNull()
        val key = showId ?: id
        if (myEntries.value.any { it.animeId == key }) return
        val show = showId?.let { AniList.cachedEntry(it) }
        batch.set(
            entryDocs(uid).document(key.toString()),
            entryMap(key, show?.title ?: n.title ?: "", show?.cover ?: n.cover, WatchStatus.PLANNED, 0, null, null, showId != null, now()),
        )
    }

    // ---- people ----

    suspend fun findUser(username: String): Profile? {
        val lower = username.trim().removePrefix("@").lowercase()
        if (lower.isEmpty()) return null
        val uid = db.collection("usernames").document(lower).get().await().getString("uid") ?: return null
        return profile(uid)
    }

    suspend fun profile(uid: String): Profile? {
        val doc = userDoc(uid).get().await()
        val name = doc.getString("username") ?: return null
        return Profile(
            uid, name, doc.getLong("createdAt") ?: 0,
            xp = doc.getLong("xp")?.toInt() ?: 0,
            level = doc.getLong("level")?.toInt()?.coerceAtLeast(1) ?: 1,
            unlocked = unlockedIn(doc),
        )
    }

    fun isFriend(uid: String) = friends.value.any { it.uid == uid }

    suspend fun sendFriendRequest(target: Profile) {
        val me = me ?: return
        if (target.uid == me.uid || isFriend(target.uid)) return
        val batch = db.batch()
        batch.set(
            db.collection("friendRequests").document("${me.uid}_${target.uid}"),
            mapOf("from" to me.uid, "to" to target.uid, "fromName" to me.username, "createdAt" to now()),
        )
        batch.set(noticeRef(target.uid), noticeMap(NoticeType.FRIEND_REQUEST, me))
        batch.commit().await()
    }

    suspend fun acceptFriend(n: Notice) {
        val me = me ?: return
        val batch = db.batch()
        batch.set(
            db.collection("users").document(me.uid).collection("friends").document(n.fromUid),
            mapOf("username" to n.fromName, "since" to now()),
        )
        batch.set(
            db.collection("users").document(n.fromUid).collection("friends").document(me.uid),
            mapOf("username" to me.username, "since" to now()),
        )
        batch.set(noticeRef(n.fromUid), noticeMap(NoticeType.FRIEND_ACCEPTED, me))
        batch.update(noticeDoc(n.id), mapOf("handled" to true, "read" to true))
        batch.commit().await()
    }

    // ---- watch together ----

    suspend fun suggest(friend: Friend, anime: Anime, message: String?) {
        val me = me ?: return
        val ref = db.collection("suggestions").document()
        val batch = db.batch()
        batch.set(
            ref,
            mapOf(
                "fromUid" to me.uid, "fromName" to me.username,
                "toUid" to friend.uid, "toName" to friend.username,
                "participants" to listOf(me.uid, friend.uid),
                "animeId" to anime.id, "title" to anime.title, "cover" to anime.cover,
                "message" to message?.takeIf { it.isNotBlank() },
                "status" to "pending", "createdAt" to now(),
            ),
        )
        batch.set(
            noticeRef(friend.uid),
            noticeMap(NoticeType.SUGGESTION, me, anime.id, anime.title, anime.cover, message?.takeIf { it.isNotBlank() }) +
                ("suggestionId" to ref.id),
        )
        batch.commit().await()
    }

    suspend fun respondToSuggestion(n: Notice, accept: Boolean) {
        val me = me ?: return
        val sid = n.suggestionId ?: return
        val batch = db.batch()
        batch.update(db.collection("suggestions").document(sid), mapOf("status" to if (accept) "accepted" else "declined"))
        batch.set(
            noticeRef(n.fromUid),
            noticeMap(NoticeType.SUGGESTION_REPLY, me, n.animeId, n.title, n.cover) + ("accepted" to accept),
        )
        batch.update(noticeDoc(n.id), mapOf("handled" to true, "read" to true))
        if (accept) addPlanned(batch, me.uid, n)
        batch.commit().await()
    }

    // ---- recommendations ----

    /** A one-way "you should watch this". It lands in the friend's inbox; nothing is tracked afterwards. */
    suspend fun recommend(friend: Friend, show: Anime, message: String?) {
        val me = me ?: return
        noticeRef(friend.uid)
            .set(noticeMap(NoticeType.RECOMMENDATION, me, show.id, show.title, show.cover, message?.takeIf { it.isNotBlank() }))
            .await()
        userDoc(me.uid).update("stats.recs", FieldValue.increment(1))
    }

    /** Answers a recommendation: add it to "Want to watch", or just clear it. */
    suspend fun answerRecommendation(n: Notice, add: Boolean) {
        val me = me ?: return
        val batch = db.batch()
        if (add) {
            addPlanned(batch, me.uid, n)
            batch.update(userDoc(me.uid), "stats.recsAdded", FieldValue.increment(1))
        }
        batch.update(noticeDoc(n.id), mapOf("handled" to true, "read" to true, "accepted" to add))
        batch.commit().await()
    }

    fun markRead(ids: List<String>) {
        if (ids.isEmpty() || myUid == null) return
        val batch = db.batch()
        ids.forEach { batch.update(noticeDoc(it), "read", true) }
        batch.commit()
    }

    // ---- helpers ----

    private fun noticeRef(toUid: String) =
        db.collection("users").document(toUid).collection("notifications").document()

    private fun noticeDoc(id: String) =
        db.collection("users").document(myUid!!).collection("notifications").document(id)

    private fun noticeMap(
        type: String, from: Profile, animeId: Int? = null, title: String? = null,
        cover: String? = null, message: String? = null,
    ): Map<String, Any?> = mapOf(
        "type" to type, "fromUid" to from.uid, "fromName" to from.username,
        "animeId" to animeId, "title" to title, "cover" to cover, "message" to message,
        "createdAt" to now(), "read" to false, "handled" to false,
    )

    private fun entryMap(
        id: Int, title: String, cover: String?, status: WatchStatus, progress: Int, total: Int?,
        label: String?, whole: Boolean, updatedAt: Long, rating: Int? = null, seen: Map<String, Int>? = null,
    ) = mapOf(
        "animeId" to id, "title" to title, "cover" to cover, "status" to status.key,
        "progress" to progress, "total" to total, "label" to label, "rating" to rating, "seen" to seen,
        "v" to if (whole) 2 else 1, "updatedAt" to updatedAt,
    )

    private fun <T> Query.listen(map: (DocumentSnapshot) -> T?): Flow<List<T>> = callbackFlow {
        val reg = addSnapshotListener { snap, err ->
            if (err != null) Log.w(TAG, "listener failed", err)
            if (snap != null) trySend(snap.documents.mapNotNull(map))
        }
        awaitClose { reg.remove() }
    }

    companion object {
        private const val TAG = "Social"
        private fun now() = System.currentTimeMillis()

        fun toEntry(d: DocumentSnapshot): Entry? {
            val status = WatchStatus.of(d.getString("status")) ?: return null
            return Entry(
                animeId = d.getLong("animeId")?.toInt() ?: d.id.toIntOrNull() ?: return null,
                title = d.getString("title") ?: "",
                cover = d.getString("cover"),
                status = status,
                progress = d.getLong("progress")?.toInt() ?: 0,
                total = d.getLong("total")?.toInt(),
                updatedAt = d.getLong("updatedAt") ?: 0,
                label = d.getString("label"),
                whole = (d.getLong("v") ?: 1) >= 2,
                // Read leniently: a rating saved as anything but a number is treated as no rating.
                rating = (d.get("rating") as? Number)?.toInt(),
                seen = (d.get("seen") as? Map<*, *>)?.mapNotNull { (k, v) ->
                    val id = (k as? String)?.toIntOrNull() ?: return@mapNotNull null
                    id to ((v as? Number)?.toInt() ?: return@mapNotNull null)
                }?.toMap(),
            )
        }

        private fun unlockedIn(d: DocumentSnapshot): Map<String, Long> =
            (d.get("ach") as? Map<*, *>)?.mapNotNull { (k, v) ->
                (k as? String ?: return@mapNotNull null) to ((v as? Number)?.toLong() ?: return@mapNotNull null)
            }?.toMap() ?: emptyMap()

        fun toRecord(d: DocumentSnapshot): Record {
            val stats = d.get("stats") as? Map<*, *>
            fun count(key: String) = (stats?.get(key) as? Number)?.toInt() ?: 0
            return Record(
                loaded = true,
                tracked = Tracked(
                    recs = count("recs"), recsAdded = count("recsAdded"), lastDay = count("lastDay"),
                    streak = count("streak"), bestStreak = count("bestStreak"),
                    dayEps = count("dayEps"), bestDay = count("bestDay"), night = count("night"),
                ),
                unlocked = unlockedIn(d),
                xp = d.getLong("xp")?.toInt() ?: 0,
                level = d.getLong("level")?.toInt() ?: 0,
                frame = Frame.of(d.getString("frame")),
                photo = d.getBlob("photo") != null,
            )
        }

        fun toNotice(d: DocumentSnapshot): Notice? {
            val type = d.getString("type") ?: return null
            val fromUid = d.getString("fromUid") ?: return null
            return Notice(
            id = d.id,
            type = type,
            fromUid = fromUid,
            fromName = d.getString("fromName") ?: "someone",
            animeId = d.getLong("animeId")?.toInt(),
            title = d.getString("title"),
            cover = d.getString("cover"),
            message = d.getString("message"),
            suggestionId = d.getString("suggestionId"),
            accepted = d.getBoolean("accepted"),
            createdAt = d.getLong("createdAt") ?: 0,
            read = d.getBoolean("read") ?: false,
            handled = d.getBoolean("handled") ?: false,
            )
        }

        fun toSuggestion(d: DocumentSnapshot): Suggestion? {
            val fromUid = d.getString("fromUid") ?: return null
            val toUid = d.getString("toUid") ?: return null
            val animeId = d.getLong("animeId")?.toInt() ?: return null
            return Suggestion(
            id = d.id,
            fromUid = fromUid,
            fromName = d.getString("fromName") ?: "",
            toUid = toUid,
            toName = d.getString("toName") ?: "",
            animeId = animeId,
            title = d.getString("title") ?: "",
            cover = d.getString("cover"),
            status = d.getString("status") ?: "pending",
            createdAt = d.getLong("createdAt") ?: 0,
            )
        }
    }
}
