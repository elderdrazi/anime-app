package com.kumo.anime.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.kumo.anime.ui.theme.Bg
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kumo.anime.Graph
import com.kumo.anime.data.Entry
import com.kumo.anime.data.Profile
import com.kumo.anime.data.WatchStatus
import com.kumo.anime.data.topRated
import com.kumo.anime.ui.AnimeCard
import com.kumo.anime.ui.Avatar
import com.kumo.anime.ui.LocalSnackbar
import com.kumo.anime.ui.theme.TextDim
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(uid: String, onBack: () -> Unit, onOpen: (Int) -> Unit, onAchievements: (String) -> Unit) {
    val social = Graph.social
    val profile by produceState<Profile?>(null, uid) { value = runCatching { social.profile(uid) }.getOrNull() }
    val entries by remember(uid) { social.entriesOf(uid) }.collectAsStateWithLifecycle(emptyList())
    val mine by social.myEntries.collectAsStateWithLifecycle()
    val friends by social.friends.collectAsStateWithLifecycle()
    val isMe = uid == social.myUid
    val isFriend = friends.any { it.uid == uid }
    var requested by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    val myPlanned = mine.filter { it.status == WatchStatus.PLANNED }.map { it.animeId }.toSet()
    val bothWant = entries.filter { it.status == WatchStatus.PLANNED && it.animeId in myPlanned }
    val myById = mine.associateBy { it.animeId }
    val top = remember(entries) { topRated(entries, Int.MAX_VALUE) }
    val bothWatching = entries.filter { it.status == WatchStatus.WATCHING && myById[it.animeId]?.status == WatchStatus.WATCHING }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // The art of their favourite shows as a cover photo, with their picture overlapping its lower edge.
                val covered = top.isNotEmpty()
                Box(Modifier.fillMaxWidth()) {
                if (covered) CoverPhoto(top, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)))
                Column(
                    Modifier.fillMaxWidth().padding(top = if (covered) 104.dp else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val name = profile?.username ?: ""
                    Box(if (covered) Modifier.clip(CircleShape).background(Bg).padding(4.dp) else Modifier) {
                        Avatar(name.ifEmpty { "?" }, uid, 76.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (name.isEmpty()) "" else "@$name", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${entries.count { it.status == WatchStatus.WATCHED }} watched · " +
                            "${entries.count { it.status == WatchStatus.PLANNED }} want to watch",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                    )
                    Spacer(Modifier.height(14.dp))
                    val p = profile
                    if (!isMe && !isFriend && p != null && social.myUid != null) {
                        if (requested) {
                            OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(12.dp)) { Text("Request sent") }
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching { social.sendFriendRequest(p) }
                                            .onSuccess { requested = true }
                                            .onFailure { snackbar.showSnackbar("Couldn't send the request") }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Add friend") }
                        }
                    }
                }
                }
            }
            // Level and achievements, as last saved by their phone. Older versions of the app save none.
            profile?.let { p ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LevelCard(p.xp, p.unlocked.size, onClick = { onAchievements(uid) })
                }
                if (p.unlocked.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { LatestBadges(p.unlocked) { onAchievements(uid) } }
                }
            }
            if (top.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TopTen(top, onOpen, "Ratings  ·  ${top.size}")
                }
            }
            if (!isMe && bothWatching.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SharedRow("You're both watching", bothWatching, onOpen) { theirs ->
                        val my = myById[theirs.animeId]?.progress ?: 0
                        when {
                            my > theirs.progress -> "You're ${my - theirs.progress} ahead"
                            my < theirs.progress -> "${theirs.progress - my} ahead of you"
                            else -> "Neck and neck"
                        }
                    }
                }
            }
            if (!isMe && bothWant.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SharedRow("You both want to watch", bothWant, onOpen) { null }
                }
            }
            entryTabs(entries, tab, { tab = it }, onOpen, emptyTitle = { "Nothing here yet" }, emptyBody = "When they add shows, they'll appear here.")
        }
    }
}

@Composable
private fun SharedRow(title: String, items: List<Entry>, onOpen: (Int) -> Unit, caption: (Entry) -> String?) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
            items(items, key = { it.animeId }) { e ->
                AnimeCard(e.title, e.cover, { onOpen(e.animeId) }, Modifier.width(100.dp), caption = caption(e))
            }
        }
    }
}
