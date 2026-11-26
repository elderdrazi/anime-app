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
import com.kumo.anime.data.Entry
import com.kumo.anime.data.Session
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
fun AccountScreen(onOpen: (Int) -> Unit, onOpenUser: (String) -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Avatar(me.username, me.uid, 68.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("@${me.username}", style = MaterialTheme.typography.headlineSmall)
                    if (since != null) Text("Member since $since", style = MaterialTheme.typography.bodySmall, color = TextDim)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(mine.count { it.status == WatchStatus.WATCHING }, "Watching", Modifier.weight(1f))
                Stat(mine.count { it.status == WatchStatus.PLANNED }, "Want to watch", Modifier.weight(1f))
                Stat(mine.count { it.status == WatchStatus.WATCHED }, "Watched", Modifier.weight(1f))
            }

            val top = remember(mine) { topRated(mine) }
            if (top.isEmpty()) {
                Card {
                    Column {
                        Text("Your top ten", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Rate shows you're watching or have watched, and your ten highest will appear here and on your profile.",
                            style = MaterialTheme.typography.bodySmall, color = TextDim,
                        )
                    }
                }
            } else {
                TopTen(top, onOpen, "Your top ${if (top.size == 10) "ten" else top.size.toString()}")
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
            Text(
                "Kumo ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall, color = TextDim,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
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

/** A person's highest-rated shows, ranked. Shown on your own profile and on the one friends see. */
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
