package com.kumo.anime.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kumo.anime.Graph
import com.kumo.anime.data.Frame
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kumo.anime.data.WatchStatus
import com.kumo.anime.ui.theme.Accent
import com.kumo.anime.ui.theme.Hairline
import com.kumo.anime.ui.theme.Mint
import com.kumo.anime.ui.theme.OnAccent
import com.kumo.anime.ui.theme.Surface1
import com.kumo.anime.ui.theme.Surface2
import com.kumo.anime.ui.theme.TextDim
import com.kumo.anime.ui.theme.TextHi
import com.kumo.anime.ui.theme.personColor
import androidx.compose.material3.SnackbarHostState

val LocalSnackbar = compositionLocalOf { SnackbarHostState() }

fun parseHex(hex: String?): Color? = runCatching {
    if (hex == null) null else Color(android.graphics.Color.parseColor(hex))
}.getOrNull()

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, tint: String? = null, radius: Dp = 12.dp) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(parseHex(tint)?.copy(alpha = 0.35f) ?: Surface2),
    )
}

@Composable
fun AnimeCard(
    title: String,
    cover: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: WatchStatus? = null,
    caption: String? = null,
    tint: String? = null,
    progress: Float? = null,
) {
    Column(modifier.clickable(onClick = onClick)) {
        Box {
            Cover(cover, Modifier.fillMaxWidth().aspectRatio(2f / 3f), tint)
            if (status != null) StatusBadge(status, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress != null) {
                ProgressBar(
                    progress,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(),
                    track = Color.Black.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = TextHi,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun statusIcon(s: WatchStatus): ImageVector = when (s) {
    WatchStatus.PLANNED -> Icons.Outlined.Bookmark
    WatchStatus.WATCHING -> Icons.Outlined.PlayArrow
    WatchStatus.WATCHED -> Icons.Outlined.Check
}

fun statusColor(s: WatchStatus): Color = when (s) {
    WatchStatus.PLANNED -> Accent
    WatchStatus.WATCHING -> Color(0xFF85B7EB)
    WatchStatus.WATCHED -> Mint
}

@Composable
fun StatusBadge(status: WatchStatus, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(statusIcon(status), status.label, tint = statusColor(status), modifier = Modifier.size(14.dp))
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Accent, track: Color = Hairline) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(50)).background(track)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp)
                .clip(RoundedCornerShape(50)).background(color),
        )
    }
}

@Composable
fun Avatar(name: String, key: String, size: Dp = 40.dp, frame: Frame? = null) {
    // [key] is the person's account id. Their picture shows once it's loaded; until then, and for
    // people without one, it's their initial on their own colour. [frame] overrides the border they
    // chose, for previewing one.
    val face by remember(key) { Graph.social.faceOf(key) }.collectAsStateWithLifecycle()
    val border = frame ?: face.frame
    // A border is a ring at the edge with a sliver of space inside it; the picture shrinks to fit.
    val ring = if (border == Frame.NONE) 0.dp else maxOf(2.dp, size * 0.07f)
    val inner = if (border == Frame.NONE) size else size - (ring + maxOf(1.5.dp, size * 0.035f)) * 2
    Box(Modifier.size(size).frameRing(border, ring), contentAlignment = Alignment.Center) {
        val photo = face.photo
        if (photo != null) {
            // The faint ring keeps a dark picture from melting into the dark background.
            Image(
                photo, "$name's picture",
                Modifier.size(inner).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            val (fg, bg) = personColor(key)
            Box(Modifier.size(inner).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Text(
                    name.take(1).uppercase(),
                    color = fg,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (inner.value * 0.42f).sp,
                )
            }
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(Surface2).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = TextDim, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextHi),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { onValueChange("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Clear", tint = TextDim, modifier = Modifier.size(18.dp))
            }
        }
        trailing?.invoke()
    }
}

/** Small rounded toggle used for sort options and genres. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, outlined: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .then(
                when {
                    selected -> Modifier.background(Accent)
                    outlined -> Modifier.border(1.dp, Hairline, shape)
                    else -> Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) OnAccent else TextDim,
            // A pill never breaks across lines; rows of them scroll sideways instead.
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TextDim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

@Composable
fun SetupNotice(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            "Sign-in isn't set up yet",
            "Lists, friends and notifications need the Firebase config file. See the README for the five-minute setup.",
        )
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(14.dp), content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(padding)) { content() }
}

fun shareProfile(ctx: Context, username: String) {
    val text = "See what I've watched and what's on my list on Kumo. Add me: @$username\nkumo://u/$username"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Share your list"))
}

fun progressLabel(progress: Int, total: Int?) = if (total != null) "Ep $progress / $total" else "Ep $progress"
