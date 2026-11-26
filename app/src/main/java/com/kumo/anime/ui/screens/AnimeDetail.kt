package com.kumo.anime.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Recommend
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import com.kumo.anime.data.Series
import com.kumo.anime.data.buildSeries
import com.kumo.anime.ui.theme.Mint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kumo.anime.Graph
import com.kumo.anime.data.AniList
import com.kumo.anime.data.Anime
import com.kumo.anime.data.FriendEntry
import com.kumo.anime.data.Session
import com.kumo.anime.data.WatchStatus
import com.kumo.anime.ui.Avatar
import com.kumo.anime.ui.Card
import com.kumo.anime.ui.Cover
import com.kumo.anime.ui.EmptyState
import com.kumo.anime.ui.LocalSnackbar
import com.kumo.anime.ui.ProgressBar
import com.kumo.anime.ui.parseHex
import com.kumo.anime.ui.statusColor
import com.kumo.anime.ui.statusIcon
import com.kumo.anime.ui.theme.Accent
import com.kumo.anime.ui.theme.Bg
import com.kumo.anime.ui.theme.Hairline
import com.kumo.anime.ui.theme.OnAccent
import com.kumo.anime.ui.theme.Surface2
import com.kumo.anime.ui.theme.TextBody
import com.kumo.anime.ui.theme.TextDim
import com.kumo.anime.ui.theme.personColor
import kotlinx.coroutines.launch

@Composable
fun AnimeDetailScreen(id: Int, onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    // [id] can be any season; the page is always for the whole show.
    var anime by remember(id) { mutableStateOf(AniList.cachedShow(id)) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(id, attempt) {
        if (anime?.full != true) {
            runCatching { AniList.show(id) }
                .onSuccess { anime = it }
                .onFailure { if (anime == null) error = it.message }
        }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            anime != null -> DetailContent(anime!!, onOpenUser)
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Couldn't load this one", error!!) {
                    Button(onClick = { error = null; attempt++ }) { Text("Try again") }
                }
            }
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
        }
        Box(
            Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(anime: Anime, onOpenUser: (String) -> Unit) {
    val social = Graph.social
    val session by social.session.collectAsStateWithLifecycle()
    val signedIn = session is Session.Ready
    val mine by social.myEntries.collectAsStateWithLifecycle()
    // The show's seasons load just after the page opens; until then it's treated as a single season.
    var loaded by remember(anime.id) { mutableStateOf(AniList.cachedSeries(anime.id)) }
    LaunchedEffect(anime.id) {
        if (loaded == null) loaded = runCatching { AniList.series(anime.id) }.getOrNull()
    }
    val series = loaded ?: remember(anime) { buildSeries(anime, listOf(anime)) }
    val entry = remember(mine, loaded, anime) { social.entryFor(anime, loaded) }
    val friends by social.friends.collectAsStateWithLifecycle()
    var friendEntries by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    LaunchedEffect(friends, anime.id, loaded) {
        if (signedIn && friends.isNotEmpty()) friendEntries = runCatching { social.friendEntries(anime, loaded) }.getOrDefault(emptyList())
    }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var sheet by remember { mutableStateOf<SendKind?>(null) }
    // The season whose episode is being typed in, by position among the released ones.
    var settingPart by remember { mutableStateOf<Int?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(false) }
    // What you've seen of each season, film and special.
    val watched = remember(entry, series) {
        if (entry == null || entry.status == WatchStatus.PLANNED) emptyMap() else series.seenOf(entry.seen, entry.progress)
    }
    val position = series.position(watched)

    fun act(block: suspend (Series) -> Unit) {
        if (!signedIn) {
            scope.launch { snackbar.showSnackbar("Set up sign-in to save your list") }
            return
        }
        scope.launch {
            // Saving needs the real season list, so wait for it if it's still on its way.
            runCatching { block(AniList.series(anime.id).also { loaded = it }) }
                .onFailure { snackbar.showSnackbar("Couldn't save. Check your connection.") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
        // Banner art with the cover overlapping it.
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            AsyncImage(
                model = anime.banner ?: anime.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(230.dp)
                    .background(parseHex(anime.color)?.copy(alpha = 0.4f) ?: Surface2),
            )
            Box(
                Modifier.fillMaxWidth().height(230.dp)
                    .background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Bg)),
            )
            // Keeps the status bar and back button readable over bright art.
            Box(
                Modifier.fillMaxWidth().height(110.dp)
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)),
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Cover(
                    anime.cover,
                    Modifier.width(112.dp).height(168.dp).border(2.dp, Bg, RoundedCornerShape(12.dp)),
                    anime.color,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text(anime.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (anime.nativeTitle != null) {
                        Text(anime.nativeTitle, style = MaterialTheme.typography.bodySmall, color = TextDim, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                    val meta = if (!series.multi) anime.meta else listOfNotNull(
                        anime.year?.toString(),
                        "${series.parts.size} parts",
                        series.total?.let { "$it eps" },
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = TextDim)
                    val extra = listOfNotNull(anime.score?.let { "$it% rating" }, anime.studio).joinToString(" · ")
                    if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = TextDim)
                }
            }
        }

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(6.dp))
            // Status: tap to set, tap again to remove.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchStatus.entries.forEach { st ->
                    StatusChoice(st, entry?.status == st, Modifier.weight(1f)) {
                        act { s -> social.setStatus(anime, s, if (entry?.status == st) null else st) }
                    }
                }
            }

            // You can score a show once you've started it.
            if (entry != null && entry.status != WatchStatus.PLANNED) {
                RatingRow(entry.rating) { rating = true }
            }

            if (entry?.status == WatchStatus.WATCHING || friendEntries.any { it.entry.status == WatchStatus.WATCHING }) {
                EpisodeTracker(
                    series = series,
                    mine = watched.takeIf { entry?.status == WatchStatus.WATCHING },
                    friends = friendEntries,
                    onStep = { d -> act { s -> social.setProgress(anime, s, s.step(social.seenFor(anime, s), d), friendEntries) } },
                    onSetExact = { settingPart = position?.index ?: 0 },
                    onOpenUser = onOpenUser,
                )
            } else if (friendEntries.isNotEmpty()) {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        friendEntries.forEach { fe ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenUser(fe.friend.uid) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(fe.friend.username, fe.friend.uid, 28.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("@${fe.friend.username}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                Text(fe.entry.status.label, style = MaterialTheme.typography.labelMedium, color = statusColor(fe.entry.status))
                            }
                        }
                    }
                }
            }

            if (series.multi) {
                SeasonList(
                    series = series,
                    watched = watched,
                    onPick = { i -> if (signedIn) settingPart = i },
                    onToggle = { i, tick ->
                        act { s ->
                            val episodes = if (tick) s.lengthOf(i) ?: 0 else 0
                            social.setProgress(anime, s, s.withPart(social.seenFor(anime, s), i, episodes), friendEntries)
                        }
                    },
                )
            }

            if (signedIn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SendKind.entries.forEach { kind ->
                        FilledTonalButton(
                            onClick = { sheet = kind },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Icon(kind.icon, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(kind.title, maxLines = 1)
                        }
                    }
                }
            }

            if (anime.genres.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    anime.genres.forEach { g ->
                        Text(
                            g,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextDim,
                            modifier = Modifier.border(1.dp, Hairline, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            if (!anime.description.isNullOrBlank()) {
                Column(Modifier.animateContentSize().clickable { expanded = !expanded }) {
                    Text("Synopsis", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        anime.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextBody,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (expanded) "Show less" else "Read more",
                        style = MaterialTheme.typography.labelLarge,
                        color = Accent,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    sheet?.let { kind -> SendSheet(kind, anime, friendEntries, onDismiss = { sheet = null }) }
    if (rating) {
        RatingDialog(
            current = entry?.rating,
            onDismiss = { rating = false },
            onSet = { value -> rating = false; act { s -> social.setRating(anime, s, value) } },
        )
    }
    settingPart?.let { index ->
        val part = series.watchable.getOrNull(index)
        val length = series.lengthOf(index)
        if (part == null) {
            settingPart = null
        } else {
            SetEpisodeDialog(
                title = if (series.multi) "${part.label}: which episode?" else "Which episode are you on?",
                // Opens on your episode in the season you're on, or the whole season for any other.
                current = watched[part.id]?.takeIf { it > 0 } ?: length ?: 1,
                total = length,
                onDismiss = { settingPart = null },
                onSet = { ep ->
                    settingPart = null
                    act { s -> social.setProgress(anime, s, s.withPart(social.seenFor(anime, s), index, ep), friendEntries) }
                },
            )
        }
    }
}

@Composable
private fun RatingRow(rating: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(shape).border(1.dp, Hairline, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (rating != null) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            null,
            Modifier.size(18.dp),
            tint = if (rating != null) Accent else TextDim,
        )
        Spacer(Modifier.width(10.dp))
        Text("Your rating", style = MaterialTheme.typography.labelLarge, color = TextBody, modifier = Modifier.weight(1f))
        if (rating != null) {
            Text("$rating", style = MaterialTheme.typography.titleMedium, color = Accent)
            Text(" / 100", style = MaterialTheme.typography.bodySmall, color = TextDim)
        } else {
            Text("Rate it", style = MaterialTheme.typography.labelLarge, color = Accent)
        }
    }
}

@Composable
private fun RatingDialog(current: Int?, onDismiss: () -> Unit, onSet: (Int?) -> Unit) {
    var value by remember { mutableStateOf((current ?: 75).toFloat()) }
    val score = value.roundToInt().coerceIn(1, 100)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your rating") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$score", style = MaterialTheme.typography.displaySmall, color = Accent)
                    Text(" / 100", style = MaterialTheme.typography.bodyMedium, color = TextDim, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton(Icons.Outlined.Remove, "One lower") { value = (score - 1).coerceAtLeast(1).toFloat() }
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = 1f..100f,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    StepButton(Icons.Outlined.Add, "One higher") { value = (score + 1).coerceAtMost(100).toFloat() }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(score) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSet(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** The two things you can send a friend from a show's page. */
private enum class SendKind(val title: String, val icon: ImageVector, val blurb: String, val hint: String) {
    TOGETHER("Watch together", Icons.Outlined.Groups, "Suggest watching %s with a friend.", "friday night?"),
    RECOMMEND("Recommend", Icons.Outlined.Recommend, "Tell a friend they should watch %s.", "you'd love this"),
}

@Composable
private fun SeasonList(series: Series, watched: Map<Int, Int>, onPick: (Int) -> Unit, onToggle: (Int, Boolean) -> Unit) {
    Card(padding = PaddingValues(vertical = 6.dp)) {
        Column {
            Text(
                "Seasons and films",
                style = MaterialTheme.typography.labelMedium,
                color = TextDim,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp),
            )
            series.parts.forEach { part ->
                val index = series.watchable.indexOf(part)
                val length = series.lengthOf(index)
                val seen = watched[part.id] ?: 0
                val done = index >= 0 && length != null && length > 0 && seen >= length
                val current = index >= 0 && !done && seen > 0
                // The row sets an episode; the circle ticks or unticks just this part.
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (index >= 0) Modifier.clickable { onPick(index) } else Modifier)
                        .padding(start = 4.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .then(
                                when {
                                    index < 0 -> Modifier
                                    // A season still airing has no last episode to tick to, so ask for one.
                                    length == null -> Modifier.clickable { onPick(index) }
                                    else -> Modifier.clickable { onToggle(index, !done) }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when {
                                done -> Icons.Outlined.CheckCircle
                                current -> Icons.Outlined.PlayCircle
                                else -> Icons.Outlined.RadioButtonUnchecked
                            },
                            contentDescription = when {
                                done -> "Watched. Tap to untick ${part.label}"
                                index >= 0 -> "Tick ${part.label} as watched"
                                else -> null
                            },
                            tint = when {
                                done -> Mint
                                current -> Accent
                                else -> Hairline
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                        Text(
                            part.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (part.released) TextBody else TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(part.meta, style = MaterialTheme.typography.labelSmall, color = TextDim, maxLines = 1)
                    }
                    if (current) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (length != null) "$seen / $length" else "Ep $seen",
                            style = MaterialTheme.typography.labelMedium,
                            color = Accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChoice(status: WatchStatus, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.height(44.dp).clip(shape)
            .then(if (selected) Modifier.background(Accent) else Modifier.border(1.dp, Hairline, shape))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(statusIcon(status), null, Modifier.size(16.dp), tint = if (selected) OnAccent else TextDim)
        Spacer(Modifier.width(6.dp))
        Text(
            if (status == WatchStatus.PLANNED) "Want" else status.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) OnAccent else TextDim,
        )
    }
}

@Composable
private fun EpisodeTracker(
    series: Series,
    /** What you've seen, part by part, or null when you aren't watching it yourself. */
    mine: Map<Int, Int>?,
    friends: List<FriendEntry>,
    onStep: (Int) -> Unit,
    onSetExact: () -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val total = series.total
    val myProgress = mine?.let { series.count(it) }
    // Unknown total (still airing): scale bars against the furthest anyone has got.
    val scaleMax = (total ?: (listOfNotNull(myProgress) + friends.map { it.entry.progress }).maxOrNull() ?: 1).coerceAtLeast(1)
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mine != null && myProgress != null) {
                val pos = series.position(mine)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(onClick = onSetExact)) {
                        if (series.multi) {
                            Text(
                                pos?.part?.label ?: series.watchable.firstOrNull()?.label ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = Accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Episode ${pos?.episode ?: 0}", style = MaterialTheme.typography.titleMedium)
                            val of = if (pos != null) pos.of else series.lengthOf(0)
                            if (of != null) Text("  / $of", style = MaterialTheme.typography.bodyMedium, color = TextDim)
                        }
                    }
                    StepButton(Icons.Outlined.Remove, "Previous episode") { onStep(-1) }
                    Spacer(Modifier.width(8.dp))
                    StepButton(Icons.Outlined.Add, "Next episode", primary = true) { onStep(1) }
                }
                ProgressBar(myProgress / scaleMax.toFloat())
            }
            val watching = friends.filter { it.entry.status != WatchStatus.PLANNED }
            if (watching.isNotEmpty()) {
                Text("Friends", style = MaterialTheme.typography.labelMedium, color = TextDim)
                watching.sortedByDescending { it.entry.progress }.forEach { fe ->
                    val done = fe.entry.status == WatchStatus.WATCHED
                    val ep = if (done) total ?: fe.entry.progress else fe.entry.progress
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenUser(fe.friend.uid) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(fe.friend.username, fe.friend.uid, 26.dp)
                        Spacer(Modifier.width(10.dp))
                        ProgressBar(ep / scaleMax.toFloat(), Modifier.weight(1f), color = personColor(fe.friend.uid).first)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (done) "done" else fe.entry.label ?: "ep $ep",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (series.multi) Modifier.widthIn(min = 44.dp, max = 132.dp) else Modifier.width(44.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (primary) Accent else Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (primary) OnAccent else TextBody, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SetEpisodeDialog(title: String, current: Int, total: Int?, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    // Starts with the current number selected, so typing replaces it.
    var field by remember {
        mutableStateOf(TextFieldValue(current.toString(), selection = TextRange(0, current.toString().length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { onSet(field.text.toIntOrNull() ?: current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { v -> field = v.copy(text = v.text.filter(Char::isDigit).take(4)) },
                singleLine = true,
                suffix = { if (total != null) Text("/ $total") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = save) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendSheet(kind: SendKind, anime: Anime, friendEntries: List<FriendEntry>, onDismiss: () -> Unit) {
    val social = Graph.social
    val friends by social.friends.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(kind.title, style = MaterialTheme.typography.titleLarge)
            Text(kind.blurb.format(anime.title), color = TextDim, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(140) },
                placeholder = { Text(kind.hint) },
                label = { Text("Note (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Hairline),
                modifier = Modifier.fillMaxWidth(),
            )
            if (friends.isEmpty()) {
                Text("Add a friend first, from the Friends tab.", color = TextDim, style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxWidth().height((friends.size.coerceAtMost(5) * 56).dp)) {
                items(friends, key = { it.uid }) { f ->
                    val theirs = friendEntries.firstOrNull { it.friend.uid == f.uid }?.entry
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(f.username, f.uid, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("@${f.username}", style = MaterialTheme.typography.labelLarge)
                            if (theirs != null) Text(theirs.status.label, style = MaterialTheme.typography.labelSmall, color = TextDim)
                        }
                        Button(
                            onClick = {
                                if (sending != null) return@Button
                                sending = f.uid
                                scope.launch {
                                    val ok = runCatching {
                                        if (kind == SendKind.TOGETHER) social.suggest(f, anime, note) else social.recommend(f, anime, note)
                                    }.isSuccess
                                    sending = null
                                    if (ok) onDismiss()
                                    snackbar.showSnackbar(if (ok) "Sent to @${f.username}" else "Couldn't send. Try again.")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (sending == f.uid) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OnAccent)
                            else Text("Send", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
