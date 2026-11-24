package com.kumo.anime.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kumo.anime.Graph
import com.kumo.anime.data.Entry
import com.kumo.anime.data.Session
import com.kumo.anime.data.Suggestion
import com.kumo.anime.data.WatchStatus
import com.kumo.anime.ui.AnimeCard
import com.kumo.anime.ui.EmptyState
import com.kumo.anime.ui.Pill
import com.kumo.anime.ui.ScreenTitle
import com.kumo.anime.ui.SetupNotice
import com.kumo.anime.ui.progressLabel
import com.kumo.anime.ui.shareProfile
import com.kumo.anime.ui.theme.TextDim

/** Tab order used on both your list and a friend's profile. */
val ListTabs = listOf(WatchStatus.WATCHING, WatchStatus.PLANNED, WatchStatus.WATCHED)

@Composable
fun LibraryScreen(onOpen: (Int) -> Unit) {
    val social = Graph.social
    val session by social.session.collectAsStateWithLifecycle()
    val me = (session as? Session.Ready)?.me
    if (me == null) {
        SetupNotice()
        return
    }
    val ctx = LocalContext.current
    val mine by social.myEntries.collectAsStateWithLifecycle()
    val suggestions by social.suggestions.collectAsStateWithLifecycle()
    val together = suggestions.filter { it.status == "accepted" }.sortedByDescending { it.createdAt }
    var tab by rememberSaveable { mutableIntStateOf(if (mine.any { it.status == WatchStatus.WATCHING }) 0 else 1) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("My list") {
            IconButton(onClick = { shareProfile(ctx, me.username) }) { Icon(Icons.Outlined.Share, "Share your list") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (together.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TogetherRow(together, me.uid, mine, onOpen)
                }
            }
            entryTabs(mine, tab, { tab = it }, onOpen, emptyTitle = { st ->
                when (st) {
                    WatchStatus.WATCHING -> "Nothing in progress"
                    WatchStatus.PLANNED -> "Start your watch list"
                    WatchStatus.WATCHED -> "Nothing finished yet"
                }
            }, emptyBody = "Find something in Discover and tap Want, Watching or Watched.")
        }
    }
}

/** Shared by My list and profiles: status pills plus the matching grid. */
fun LazyGridScope.entryTabs(
    entries: List<Entry>,
    tab: Int,
    onTab: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    emptyTitle: (WatchStatus) -> String,
    emptyBody: String,
) {
    val status = ListTabs[tab]
    item(span = { GridItemSpan(maxLineSpan) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            ListTabs.forEachIndexed { i, st ->
                val n = entries.count { it.status == st }
                Pill("${st.label}  $n", tab == i, { onTab(i) }, outlined = false)
            }
        }
    }
    val shown = entries.filter { it.status == status }.sortedByDescending { it.updatedAt }
    if (shown.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(emptyTitle(status), emptyBody) }
    }
    items(shown, key = { it.animeId }) { e ->
        AnimeCard(
            title = e.title,
            cover = e.cover,
            onClick = { onOpen(e.animeId) },
            caption = if (e.status == WatchStatus.WATCHING) progressLabel(e.progress, e.total) else null,
            progress = if (e.status == WatchStatus.WATCHING && e.total != null && e.total > 0) e.progress / e.total.toFloat() else null,
        )
    }
}

@Composable
private fun TogetherRow(together: List<Suggestion>, myUid: String, mine: List<Entry>, onOpen: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("Watching together", style = MaterialTheme.typography.titleSmall)
        Text("Shows you and a friend agreed on", style = MaterialTheme.typography.labelSmall, color = TextDim)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
            items(together, key = { it.id }) { s ->
                val e = mine.firstOrNull { it.animeId == s.animeId }
                AnimeCard(
                    title = s.title,
                    cover = s.cover,
                    onClick = { onOpen(s.animeId) },
                    caption = "with @${s.partnerName(myUid)}",
                    status = e?.status,
                    modifier = Modifier.width(100.dp),
                )
            }
        }
    }
}
