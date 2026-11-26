package com.kumo.anime.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
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

data class Profile(val uid: String, val username: String)

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
)

data class Friend(val uid: String, val username: String)

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

    val myEntries: StateFlow<List<Entry>> = whenReady(emptyList()) { entriesOf(it.uid) }
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

    private var upgrading = false

    fun start() {
        if (!enabled) return
        if (!upgrading) {
            upgrading = true
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
                val name = db.collection("users").document(user.uid).get().await().getString("username")
                if (name == null) Session.NeedsUsername else Session.Ready(Profile(user.uid, name))
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
                Session.Failed("Couldn't connect. Check your connection and try again.")
            }
        }
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
        _session.value = Session.Ready(Profile(uid, name))
    }

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
        var progress = 0
        for (e in found) {
            val reached = if (e.whole && e.animeId == show.id) {
                e.progress
            } else {
                val i = series.watchable.indexOfFirst { it.id == e.animeId }
                when {
                    i < 0 || e.status == WatchStatus.PLANNED -> 0
                    e.status == WatchStatus.WATCHED -> series.offsetOf(i) + (series.lengthOf(i) ?: e.progress)
                    e.progress > 0 -> series.offsetOf(i) + e.progress
                    else -> 0
                }
            }
            progress = maxOf(progress, reached)
        }
        progress = series.clamp(progress)
        val total = series.total
        val status = when {
            found.all { it.status == WatchStatus.PLANNED } -> WatchStatus.PLANNED
            total != null && total > 0 && progress >= total -> WatchStatus.WATCHED
            else -> WatchStatus.WATCHING
        }
        return Entry(
            show.id, show.title, show.cover, status, progress, total,
            found.maxOf { it.updatedAt }, series.label(progress), whole = false,
        )
    }

    /** Writes the single entry for a show and clears any per-season ones it replaces. */
    private fun save(
        batch: com.google.firebase.firestore.WriteBatch, uid: String, show: Anime, series: Series,
        status: WatchStatus, progress: Int, updatedAt: Long = now(),
    ) {
        val docs = entryDocs(uid)
        batch.set(
            docs.document(show.id.toString()),
            entryMap(show.id, show.title, show.cover, status, progress, series.total, series.label(progress), true, updatedAt),
        )
        myEntries.value.filter { it.animeId in series.memberIds && it.animeId != show.id }
            .forEach { batch.delete(docs.document(it.animeId.toString())) }
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
        val total = series.total
        val progress = when (status) {
            WatchStatus.WATCHED -> total ?: old?.progress ?: 0
            WatchStatus.PLANNED -> 0
            // Picking a finished show back up (a new season came out) carries on from where you were.
            WatchStatus.WATCHING -> old?.progress?.takeIf {
                old.status == WatchStatus.WATCHING || (old.status == WatchStatus.WATCHED && total != null && it < total)
            } ?: 0
        }
        save(batch, uid, show, series, status, progress)
        batch.commit().await()
    }

    /**
     * Sets how many episodes of the show you've seen, counted across all its seasons.
     * Reaching the last released episode marks it watched, and friends you pass are told.
     */
    suspend fun setProgress(show: Anime, series: Series, episode: Int, friendEntries: List<FriendEntry>) {
        val uid = myUid ?: return
        val me = me ?: return
        val total = series.total
        val ep = series.clamp(episode)
        val oldEp = entryFor(show, series)?.progress ?: 0
        val status = if (total != null && total > 0 && ep >= total) WatchStatus.WATCHED else WatchStatus.WATCHING
        val batch = db.batch()
        save(batch, uid, show, series, status, ep)
        friendEntries.filter { it.entry.status == WatchStatus.WATCHING && oldEp < it.entry.progress && ep >= it.entry.progress && it.entry.progress > 0 }
            .forEach { fe ->
                batch.set(
                    noticeRef(fe.friend.uid),
                    noticeMap(NoticeType.CAUGHT_UP, me, show.id, show.title, show.cover, series.describe(ep)),
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
                save(batch, uid, show, series, merged.status, merged.progress, merged.updatedAt)
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

    suspend fun profile(uid: String): Profile? =
        db.collection("users").document(uid).get().await().getString("username")?.let { Profile(uid, it) }

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
    }

    /** Answers a recommendation: add it to "Want to watch", or just clear it. */
    suspend fun answerRecommendation(n: Notice, add: Boolean) {
        val me = me ?: return
        val batch = db.batch()
        if (add) addPlanned(batch, me.uid, n)
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
        label: String?, whole: Boolean, updatedAt: Long,
    ) = mapOf(
        "animeId" to id, "title" to title, "cover" to cover, "status" to status.key,
        "progress" to progress, "total" to total, "label" to label,
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
