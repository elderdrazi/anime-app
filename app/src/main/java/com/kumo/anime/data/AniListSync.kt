package com.kumo.anime.data

import android.content.Context
import android.util.Log
import com.kumo.anime.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The AniList account connected on this phone, for one Kumo account. */
data class AniListLink(val uid: String, val name: String, val syncedAt: Long)

sealed interface SyncState {
    data object Idle : SyncState
    /** [of] is 0 while the list is still being read and sorted into shows. */
    data class Running(val done: Int, val of: Int) : SyncState
    data class Done(val result: ImportResult) : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Keeps your Kumo list topped up from an AniList account. It reads the public list by username, so
 * there's no AniList sign-in and nothing is ever written back to AniList.
 *
 * After the first import only rows that changed on AniList are looked at, which keeps a routine
 * sync to a single small request.
 */
object AniListSync {
    private const val TAG = "AniListSync"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val busy = Mutex()

    private val _link = MutableStateFlow<AniListLink?>(null)
    val link: StateFlow<AniListLink?> = _link.asStateFlow()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val prefs get() = app.getSharedPreferences("anilist", Context.MODE_PRIVATE)
    /** The rows as they stood at the last sync: AniList entry id to when it last changed. */
    private val seenFile get() = File(app.filesDir, "anilist-rows.txt")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val uid = prefs.getString("uid", null)
        val name = prefs.getString("name", null)
        if (uid != null && name != null) _link.value = AniListLink(uid, name, prefs.getLong("syncedAt", 0))
    }

    /** The connection for the Kumo account that's signed in, if there is one. */
    fun linkFor(uid: String?): AniListLink? = _link.value?.takeIf { it.uid == uid }

    /**
     * Connects an AniList username and starts the first import. Throws with a message fit to show
     * if the username doesn't exist or its list is private.
     */
    suspend fun connect(username: String) {
        val uid = Graph.social.myUid ?: throw AniListException("You're not signed in.")
        val name = username.trim().removePrefix("@")
        if (name.isEmpty()) throw AniListException("Type your AniList username.")
        val list = AniList.userList(name)
        withContext(Dispatchers.IO) { seenFile.delete() }
        setLink(AniListLink(uid, list.name, 0))
        scope.launch { sync(list) }
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        seenFile.delete()
        _link.value = null
        _state.value = SyncState.Idle
    }

    /** Runs in the app's own scope, so leaving the screen doesn't cut a sync short. */
    fun syncNow(minGap: Long = 0) {
        scope.launch { sync(minGap) }
    }

    /** Syncs unless one ran within [minGap]. Quiet when nothing is connected. */
    suspend fun sync(minGap: Long = 0) {
        val link = linkFor(Graph.social.myUid) ?: return
        if (System.currentTimeMillis() - link.syncedAt < minGap) return
        sync(null)
    }

    /** For the background check: waits for the account to load, then syncs if it's been a while. */
    suspend fun syncInBackground(minGap: Long) {
        if (_link.value == null) return
        withTimeoutOrNull(20_000) { Graph.social.session.first { it is Session.Ready } } ?: return
        sync(minGap)
    }

    private suspend fun sync(fetched: UserList?) {
        if (!busy.tryLock()) return
        try {
            val link = linkFor(Graph.social.myUid) ?: return
            _state.value = SyncState.Running(0, 0)
            val list = fetched ?: AniList.userList(link.name)
            val before = withContext(Dispatchers.IO) { readSeen() }
            val changed = list.rows.filter { before[it.mediaId] != it.updatedAt }.map { it.mediaId }
            val result = if (changed.isEmpty()) ImportResult()
            else Graph.social.importRows(list.rows, changed) { done, of -> _state.value = SyncState.Running(done, of) }
            withContext(Dispatchers.IO) { writeSeen(list.rows) }
            // Disconnected or signed out while this ran: don't bring the connection back.
            if (linkFor(Graph.social.myUid)?.name == link.name) setLink(link.copy(syncedAt = System.currentTimeMillis()))
            _state.value = SyncState.Done(result)
            Log.i(TAG, "synced ${list.rows.size} rows, ${changed.size} changed: $result")
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            _state.value = SyncState.Failed(
                (e as? AniListException)?.message ?: (e as? AccountException)?.message
                    ?: "Couldn't sync with AniList. Check your connection and try again.",
            )
        } finally {
            busy.unlock()
        }
    }

    private fun setLink(link: AniListLink) {
        prefs.edit().putString("uid", link.uid).putString("name", link.name).putLong("syncedAt", link.syncedAt).apply()
        _link.value = link
    }

    private fun readSeen(): Map<Int, Long> = runCatching {
        seenFile.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val (a, b) = line.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            (a.toIntOrNull() ?: return@mapNotNull null) to (b.toLongOrNull() ?: return@mapNotNull null)
        }?.toMap()
    }.getOrNull() ?: emptyMap()

    private fun writeSeen(rows: List<ListRow>) {
        runCatching { seenFile.writeText(rows.joinToString("\n") { "${it.mediaId}=${it.updatedAt}" }) }
    }
}
