package com.kumo.anime.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kumo.anime.BuildConfig
import com.kumo.anime.Graph
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import com.kumo.anime.ui.theme.Bg
import com.kumo.anime.ui.theme.Surface2
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.LinearProgressIndicator
import com.kumo.anime.data.AniListException
import com.kumo.anime.data.AniListSync
import com.kumo.anime.data.Entry
import com.kumo.anime.data.Progression
import com.kumo.anime.data.Session
import com.kumo.anime.data.SyncState
import com.kumo.anime.data.UpdateState
import com.kumo.anime.data.Updater
import com.kumo.anime.ui.ProgressBar
import androidx.compose.material3.Button
import com.kumo.anime.data.WatchStatus
import com.kumo.anime.data.topRated
import com.kumo.anime.ui.AnimeCard
import com.kumo.anime.ui.Avatar
import com.kumo.anime.ui.Card
import com.kumo.anime.ui.LocalSnackbar
import com.kumo.anime.ui.ScreenTitle
import com.kumo.anime.ui.SetupNotice
import com.kumo.anime.ui.shareProfile
import com.kumo.anime.ui.theme.Accent
import com.kumo.anime.ui.theme.Hairline
import com.kumo.anime.ui.theme.TextBody
import com.kumo.anime.ui.theme.TextDim
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Your own profile: who you are, your top ten, and your account details. */
@Composable
fun AccountScreen(onOpen: (Int) -> Unit, onOpenUser: (String) -> Unit, onAchievements: (String) -> Unit) {
    val social = Graph.social
    val session by social.session.collectAsStateWithLifecycle()
    val me = (session as? Session.Ready)?.me
    if (me == null) {
        SetupNotice()
        return
    }
    val ctx = LocalContext.current
    val mine by social.myEntries.collectAsStateWithLifecycle()
    val email by social.email.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var addingEmail by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val face by remember(me.uid) { social.faceOf(me.uid) }.collectAsStateWithLifecycle()
    val photo = face.photo
    val standing by Progression.standing.collectAsStateWithLifecycle()
    val record by social.record.collectAsStateWithLifecycle()
    var choosingBorder by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<Uri?>(null) }
    // Android's own photo picker: it hands over just the one picture chosen, so no storage permission is needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { cropping = it }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val since = remember(me.createdAt) {
        if (me.createdAt > 0) SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(me.createdAt)) else null
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Profile") {
            IconButton(onClick = { shareProfile(ctx, me.username) }) { Icon(Icons.Outlined.Share, "Share your profile") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            UpdateCard()
            // With at least one show rated, the art of your favourites becomes a cover photo behind your name.
            val top = remember(mine) { topRated(mine, Int.MAX_VALUE) }
            Box(Modifier.fillMaxWidth()) {
            if (top.isNotEmpty()) CoverPhoto(top, Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(20.dp)))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (top.isNotEmpty()) Modifier.align(Alignment.BottomStart).padding(14.dp) else Modifier.padding(horizontal = 4.dp),
            ) {
                // Tapping your picture changes it. The camera badge is the hint that it can be tapped.
                Box {
                    Box(
                        Modifier.clip(CircleShape).clickable(onClickLabel = "Change your picture or border") { photoMenu = true },
                    ) { Avatar(me.username, me.uid, 68.dp) }
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(26.dp)
                            .clip(CircleShape).background(Bg).padding(2.dp).clip(CircleShape).background(Surface2),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(13.dp), tint = TextBody) }
                    DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (photo == null) "Choose a picture" else "Choose a new picture") },
                            onClick = { photoMenu = false; pickPhoto() },
                        )
                        DropdownMenuItem(text = { Text("Choose a border") }, onClick = { photoMenu = false; choosingBorder = true })
                        if (photo != null) {
                            DropdownMenuItem(
                                text = { Text("Remove picture") },
                                onClick = {
                                    photoMenu = false
                                    scope.launch {
                                        runCatching { social.setPhoto(null) }
                                            .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't remove your picture.") }
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("@${me.username}", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (since != null) {
                        Text(
                            "Member since $since", style = MaterialTheme.typography.bodySmall,
                            color = if (top.isNotEmpty()) TextBody else TextDim,
                        )
                    }
                }
            }
            }

            // Level and achievements. Shown from what was last saved until the live numbers are ready.
            LevelCard(
                xp = standing?.xp ?: record.xp,
                unlocked = standing?.unlocked?.size ?: record.unlocked.size,
                onClick = { onAchievements(me.uid) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(mine.count { it.status == WatchStatus.WATCHING }, "Watching", Modifier.weight(1f))
                Stat(mine.count { it.status == WatchStatus.PLANNED }, "Want to watch", Modifier.weight(1f))
                Stat(mine.count { it.status == WatchStatus.WATCHED }, "Watched", Modifier.weight(1f))
            }

            if (top.isEmpty()) {
                Card {
                    Column {
                        Text("Your ratings", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Rate shows you're watching or have watched, and they'll be ranked here and on your profile, best first.",
                            style = MaterialTheme.typography.bodySmall, color = TextDim,
                        )
                    }
                }
            } else {
                // Every show you've rated, best first; the first ten are your top ten.
                TopTen(top, onOpen, "Your ratings  ·  ${top.size}")
            }

            Card(padding = PaddingValues(vertical = 4.dp)) {
                Column {
                    Text(
                        "Account",
                        style = MaterialTheme.typography.labelMedium, color = TextDim,
                        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                    )
                    Detail("Username", "@${me.username}")
                    HorizontalDivider(color = Hairline, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 14.dp))
                    if (email != null) {
                        Detail("Email", email!!)
                    } else {
                        Detail("Email", "Add", accent = true) { addingEmail = true }
                    }
                    if (since != null) {
                        HorizontalDivider(color = Hairline, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 14.dp))
                        Detail("Member since", since)
                    }
                }
            }
            if (email == null) {
                Text(
                    "Your account lives on this phone for now. Add an email to keep your list if you switch phones or reinstall. Friends never see your email.",
                    style = MaterialTheme.typography.bodySmall, color = TextDim,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            AniListCard(me.uid)

            OutlinedButton(
                onClick = { onOpenUser(me.uid) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text("See what friends see") }
            if (email != null) {
                TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Sign out")
                }
            }
            val update by Updater.state.collectAsStateWithLifecycle()
            TextButton(
                onClick = {
                    scope.launch {
                        Updater.check(loud = true)
                        when (val now = Updater.state.value) {
                            UpdateState.Current -> snackbar.showSnackbar("You're on the latest version.")
                            is UpdateState.Failed -> snackbar.showSnackbar(now.message)
                            else -> {}
                        }
                    }
                },
                enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    if (update is UpdateState.Checking) "Checking for updates…" else "Kumo ${BuildConfig.VERSION_NAME} · Check for updates",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    if (choosingBorder) {
        BorderDialog(
            me.username, me.uid,
            level = standing?.level ?: record.level,
            achievements = standing?.unlocked?.size ?: record.unlocked.size,
            current = face.frame,
            onDismiss = { choosingBorder = false },
        )
    }
    cropping?.let { uri ->
        PhotoCropDialog(
            uri,
            onDismiss = { cropping = null },
            onUse = { jpeg ->
                social.setPhoto(jpeg)
                cropping = null
            },
        )
    }
    if (addingEmail) {
        CredentialsDialog(
            title = "Add your email",
            blurb = "Pick a password too. You'll use both to open your account on another phone.",
            confirm = "Add",
            onDismiss = { addingEmail = false },
            onSubmit = { e, p ->
                social.addEmail(e, p)
                addingEmail = false
                scope.launch { snackbar.showSnackbar("Email added. Your list is safe now.") }
            },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your list stays saved. Sign back in with ${email ?: "your email"} and your password.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; social.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/** Appears only when a newer version is out. One tap downloads it and opens Android's install prompt. */
@Composable
private fun UpdateCard() {
    val state by Updater.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val update = when (val s = state) {
        is UpdateState.Available -> s
        is UpdateState.Downloading -> s.update
        is UpdateState.Failed -> s.update
        else -> null
    } ?: return
    val downloading = state as? UpdateState.Downloading
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kumo ${update.version} is out", style = MaterialTheme.typography.titleSmall)
                    Text(
                        (state as? UpdateState.Failed)?.message
                            ?: if (downloading != null) "Downloading…" else "You have ${BuildConfig.VERSION_NAME}. Your list stays as it is.",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { scope.launch { Updater.install(ctx, update) } },
                    enabled = downloading == null,
                    shape = RoundedCornerShape(10.dp),
                ) { Text(if (state is UpdateState.Failed) "Retry" else "Update") }
            }
            if (downloading != null) ProgressBar(downloading.fraction, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Connects an AniList account so the list there flows into Kumo, and shows how the last sync went.
 * Kumo only reads from AniList; it never changes anything there.
 */
@Composable
private fun AniListCard(uid: String) {
    val linked by AniListSync.link.collectAsStateWithLifecycle()
    val state by AniListSync.state.collectAsStateWithLifecycle()
    val link = linked?.takeIf { it.uid == uid }
    var connecting by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Card {
        if (link == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AniList", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Track on AniList too? Connect it to bring your list in and keep it topped up.",
                        style = MaterialTheme.typography.bodySmall, color = TextDim,
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { connecting = true }, shape = RoundedCornerShape(10.dp)) { Text("Connect") }
            }
        } else {
            val running = state as? SyncState.Running
            Column {
                Text("AniList · ${link.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    syncLine(state, link.syncedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is SyncState.Failed) MaterialTheme.colorScheme.error else TextDim,
                )
                if (running != null) {
                    Spacer(Modifier.height(10.dp))
                    if (running.of > 0) {
                        ProgressBar(running.done / running.of.toFloat(), Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().height(4.dp), color = Accent, trackColor = Hairline,
                        )
                    }
                }
                Row(Modifier.padding(top = 4.dp).offset(x = (-12).dp)) {
                    TextButton(onClick = { AniListSync.syncNow() }, enabled = running == null) { Text("Sync now") }
                    TextButton(onClick = { confirmDisconnect = true }, enabled = running == null) { Text("Disconnect") }
                }
            }
        }
    }

    if (connecting) AniListDialog { connecting = false }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect AniList?") },
            text = { Text("Kumo stops checking your AniList list. Everything already brought in stays on your Kumo list.") },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; AniListSync.disconnect() }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

private fun syncLine(state: SyncState, syncedAt: Long): String = when (state) {
    is SyncState.Running ->
        if (state.of == 0) "Reading your AniList list…" else "Bringing in ${state.done} of ${state.of} shows…"
    is SyncState.Failed -> state.message
    is SyncState.Done -> {
        val r = state.result
        val did = listOfNotNull(
            r.added.takeIf { it > 0 }?.let { "added $it ${if (it == 1) "show" else "shows"}" },
            r.updated.takeIf { it > 0 }?.let { if (r.added > 0) "updated $it" else "updated $it ${if (it == 1) "show" else "shows"}" },
        ).joinToString(" and ").replaceFirstChar { it.uppercase() }
        val left = r.leftOut.takeIf { it > 0 }?.let { "$it left out, such as ones you dropped." }
        if (did.isEmpty() && left == null) "Up to date. Checked just now."
        else listOfNotNull(did.takeIf { it.isNotEmpty() }?.let { "$it." }, left).joinToString(" ")
    }
    SyncState.Idle -> if (syncedAt == 0L) "Not synced yet." else "Synced ${ago(syncedAt)}."
}

private fun ago(time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / 60_000
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 120 -> "an hour ago"
        minutes < 48 * 60 -> "${minutes / 60} hours ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** Asks for an AniList username. The list is checked before the dialog closes, so typos are caught here. */
@Composable
private fun AniListDialog(onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        if (name.isBlank()) {
            error = "Type your AniList username."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                AniListSync.connect(name)
                onDismiss()
            } catch (e: Exception) {
                error = (e as? AniListException)?.message ?: "Couldn't reach AniList. Check your connection and try again."
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect AniList") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Kumo reads your public AniList list and adds it to yours here, with each show's seasons joined into one. " +
                        "It only ever adds: nothing changes on AniList, and nothing here is removed or set back.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim(); error = null },
                    label = { Text("AniList username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onDismiss() }) { Text("Cancel") } },
    )
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier = Modifier) {
    Card(modifier, padding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextDim, maxLines = 1)
        }
    }
}

@Composable
private fun Detail(label: String, value: String, accent: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextDim, modifier = Modifier.width(112.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (accent) Accent else TextBody,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Every show a person has rated, ranked best first, in a row that scrolls sideways. On both kinds of profile. */
@Composable
fun TopTen(top: List<Entry>, onOpen: (Int) -> Unit, title: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
            itemsIndexed(top, key = { _, e -> e.animeId }) { i, e ->
                AnimeCard(e.title, e.cover, { onOpen(e.animeId) }, Modifier.width(100.dp), caption = "#${i + 1} · ${e.rating} / 100")
            }
        }
    }
}

/** Email and password entry, used to attach an email and to sign back in. */
@Composable
fun CredentialsDialog(
    title: String,
    blurb: String,
    confirm: String,
    onDismiss: () -> Unit,
    onForgot: (suspend (String) -> Unit)? = null,
    onSubmit: suspend (String, String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        note = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: "That didn't work. Try again."
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(blurb, style = MaterialTheme.typography.bodyMedium, color = TextDim)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim(); error = null },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextDim) }
                if (onForgot != null) {
                    Text(
                        "Forgot your password?",
                        style = MaterialTheme.typography.labelLarge,
                        color = Accent,
                        modifier = Modifier.clickable {
                            if (!email.contains('@')) error = "Type your email first."
                            else run { onForgot(email); note = "Check $email for a reset link." }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        !email.contains('@') -> error = "That doesn't look like an email."
                        password.length < 6 -> error = "Use a password of at least 6 characters."
                        else -> run { onSubmit(email, password) }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onDismiss() }) { Text("Cancel") } },
    )
}
