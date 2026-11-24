package com.kumo.anime.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kumo.anime.Graph
import com.kumo.anime.data.Profile
import com.kumo.anime.data.Session
import com.kumo.anime.ui.Avatar
import com.kumo.anime.ui.Card
import com.kumo.anime.ui.EmptyState
import com.kumo.anime.ui.LocalSnackbar
import com.kumo.anime.ui.ScreenTitle
import com.kumo.anime.ui.SearchField
import com.kumo.anime.ui.SetupNotice
import com.kumo.anime.ui.shareProfile
import com.kumo.anime.ui.theme.Hairline
import com.kumo.anime.ui.theme.TextDim
import kotlinx.coroutines.launch

private sealed interface Lookup {
    data object Idle : Lookup
    data object Searching : Lookup
    data object NotFound : Lookup
    data class Found(val profile: Profile) : Lookup
}

@Composable
fun FriendsScreen(onOpenUser: (String) -> Unit) {
    val social = Graph.social
    val session by social.session.collectAsStateWithLifecycle()
    val me = (session as? Session.Ready)?.me
    if (me == null) {
        SetupNotice()
        return
    }
    val ctx = LocalContext.current
    val friends by social.friends.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var query by remember { mutableStateOf("") }
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }
    var requested by remember { mutableStateOf(setOf<String>()) }

    fun search() {
        if (query.isBlank()) return
        lookup = Lookup.Searching
        scope.launch {
            lookup = runCatching { social.findUser(query) }.getOrNull()?.let { Lookup.Found(it) } ?: Lookup.NotFound
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Friends") {
            IconButton(onClick = { shareProfile(ctx, me.username) }) { Icon(Icons.Outlined.Share, "Share your profile") }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
            item {
                SearchField(
                    value = query,
                    onValueChange = { query = it; lookup = Lookup.Idle },
                    placeholder = "Add by username",
                    onSubmit = ::search,
                    trailing = { if (query.isNotBlank()) TextButton(onClick = ::search) { Text("Find") } },
                )
                Spacer(Modifier.height(10.dp))
                when (val l = lookup) {
                    Lookup.Idle -> Text(
                        "You're @${me.username}. Share it so friends can add you.",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Lookup.Searching -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    Lookup.NotFound -> Text(
                        "No one goes by @${query.trim().removePrefix("@")}. Check the spelling.",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    is Lookup.Found -> Card {
                        val p = l.profile
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.username, p.uid)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "@${p.username}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f).clickable { onOpenUser(p.uid) },
                            )
                            when {
                                p.uid == me.uid -> Text("That's you", color = TextDim, style = MaterialTheme.typography.labelMedium)
                                friends.any { it.uid == p.uid } -> TextButton(onClick = { onOpenUser(p.uid) }) { Text("View") }
                                p.uid in requested -> Text("Requested", color = TextDim, style = MaterialTheme.typography.labelMedium)
                                else -> Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching { social.sendFriendRequest(p) }
                                                .onSuccess { requested = requested + p.uid }
                                                .onFailure { snackbar.showSnackbar("Couldn't send the request") }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                ) { Text("Add") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (friends.isNotEmpty()) {
                    Text("Your friends", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
                }
            }
            if (friends.isEmpty()) {
                item { EmptyState("Bring your friends", "Add someone by username, or share yours so they can see your list.") }
            }
            items(friends.sortedBy { it.username.lowercase() }, key = { it.uid }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenUser(f.uid) }.padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(f.username, f.uid)
                    Spacer(Modifier.width(14.dp))
                    Text("@${f.username}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = TextDim)
                }
                HorizontalDivider(color = Hairline, thickness = 0.5.dp)
            }
        }
    }
}
