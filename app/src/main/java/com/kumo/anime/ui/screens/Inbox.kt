package com.kumo.anime.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kumo.anime.Graph
import com.kumo.anime.data.Notice
import com.kumo.anime.data.NoticeType
import com.kumo.anime.data.Session
import com.kumo.anime.notify.Notifier
import com.kumo.anime.ui.Avatar
import com.kumo.anime.ui.Cover
import com.kumo.anime.ui.EmptyState
import com.kumo.anime.ui.LocalSnackbar
import com.kumo.anime.ui.ScreenTitle
import com.kumo.anime.ui.SetupNotice
import com.kumo.anime.ui.theme.Accent
import com.kumo.anime.ui.theme.Hairline
import com.kumo.anime.ui.theme.TextDim
import com.kumo.anime.ui.theme.TextHi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun InboxScreen(onOpen: (Int) -> Unit, onOpenUser: (String) -> Unit) {
    val social = Graph.social
    val session by social.session.collectAsStateWithLifecycle()
    if (session !is Session.Ready) {
        SetupNotice()
        return
    }
    val notices by social.notices.collectAsStateWithLifecycle()
    // Seeing the inbox counts as reading it; keep the dots visible for a moment first.
    val unreadIds = notices.filter { !it.read }.map { it.id }
    LaunchedEffect(unreadIds) {
        if (unreadIds.isNotEmpty()) {
            delay(1500)
            social.markRead(unreadIds)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Inbox")
        if (notices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("All quiet", "Friend requests and watch-together suggestions show up here.")
            }
            return
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(notices, key = { it.id }) { n ->
                NoticeRow(n, onOpen, onOpenUser)
                HorizontalDivider(color = Hairline, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun NoticeRow(n: Notice, onOpen: (Int) -> Unit, onOpenUser: (String) -> Unit) {
    val social = Graph.social
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val (who, rest) = Notifier.text(n)
    val body = if (n.type == NoticeType.SUGGESTION) rest.substringBefore(" · \"") else rest

    fun run(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { snackbar.showSnackbar("Couldn't do that. Check your connection.") }
    }

    Row(
        Modifier.fillMaxWidth()
            .clickable { if (n.animeId != null) onOpen(n.animeId) else onOpenUser(n.fromUid) }
            .padding(vertical = 14.dp),
    ) {
        if (n.cover != null) {
            Cover(n.cover, Modifier.width(44.dp).height(64.dp), radius = 8.dp)
        } else {
            Avatar(n.fromName, n.fromUid, 44.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = TextHi)) { append(who) }
                    append(" ")
                    append(body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextHi,
            )
            if (n.type == NoticeType.SUGGESTION && n.message != null) {
                Text("“${n.message}”", style = MaterialTheme.typography.bodyMedium, color = TextDim, fontStyle = FontStyle.Italic)
            }
            Text(
                DateUtils.getRelativeTimeSpanString(n.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
            val actionable = n.type == NoticeType.FRIEND_REQUEST || n.type == NoticeType.SUGGESTION
            if (actionable && !n.handled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (n.type == NoticeType.FRIEND_REQUEST) {
                        Button(onClick = { run { social.acceptFriend(n) } }, shape = RoundedCornerShape(10.dp)) { Text("Accept") }
                    } else {
                        Button(onClick = { run { social.respondToSuggestion(n, true) } }, shape = RoundedCornerShape(10.dp)) { Text("I'm in") }
                        OutlinedButton(onClick = { run { social.respondToSuggestion(n, false) } }, shape = RoundedCornerShape(10.dp)) { Text("Pass") }
                    }
                }
            } else if (actionable) {
                Text("Done", style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
        }
        if (!n.read) {
            Box(Modifier.padding(start = 8.dp, top = 6.dp).size(8.dp).clip(CircleShape).background(Accent))
        }
    }
}
