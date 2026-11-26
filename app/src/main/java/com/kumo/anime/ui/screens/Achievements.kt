package com.kumo.anime.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kumo.anime.Graph
import com.kumo.anime.data.ACHIEVEMENTS
import com.kumo.anime.data.ACHIEVEMENTS_BY_ID
import com.kumo.anime.data.Achievement
import com.kumo.anime.data.Frame
import com.kumo.anime.data.Group
import com.kumo.anime.data.Profile
import com.kumo.anime.data.Progression
import com.kumo.anime.data.Stats
import com.kumo.anime.data.Tier
import com.kumo.anime.data.levelFor
import com.kumo.anime.data.rankFor
import com.kumo.anime.data.xpForLevel
import com.kumo.anime.ui.Avatar
import com.kumo.anime.ui.Card
import com.kumo.anime.ui.Pill
import com.kumo.anime.ui.ProgressBar
import com.kumo.anime.ui.theme.Accent
import com.kumo.anime.ui.theme.Surface2
import com.kumo.anime.ui.theme.TextBody
import com.kumo.anime.ui.theme.TextDim
import com.kumo.anime.ui.theme.TextHi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun tierColor(tier: Tier): Color = when (tier) {
    Tier.BRONZE -> Color(0xFFD9976A)
    Tier.SILVER -> Color(0xFFC9D0DC)
    Tier.GOLD -> Color(0xFFF4C84F)
    Tier.PLATINUM -> Color(0xFF8FE9F7)
}

private fun groupIcon(group: Group): ImageVector = when (group) {
    Group.EPISODES -> Icons.Outlined.PlayArrow
    Group.SHOWS -> Icons.Outlined.DoneAll
    Group.RATINGS -> Icons.Outlined.Star
    Group.LIBRARY -> Icons.Outlined.Bookmarks
    Group.DEDICATION -> Icons.Outlined.LocalFireDepartment
    Group.GENRES -> Icons.Outlined.Category
    Group.VARIETY -> Icons.Outlined.Explore
    Group.TASTE -> Icons.Outlined.Diamond
    Group.SOCIAL -> Icons.Outlined.People
    Group.HABITS -> Icons.Outlined.Bolt
    Group.PROFILE -> Icons.Outlined.Person
    Group.LEVELS -> Icons.Outlined.MilitaryTech
}

private fun number(n: Int) = "%,d".format(n)

/** The round badge for an achievement: its tier's colour once unlocked, grey before. */
@Composable
fun Medal(a: Achievement, unlocked: Boolean, size: Dp = 44.dp) {
    val color = tierColor(a.tier)
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(
                if (unlocked) Brush.linearGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0.12f)))
                else Brush.linearGradient(listOf(Surface2, Surface2)),
            )
            .border(1.5.dp, if (unlocked) color else Color.White.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(groupIcon(a.group), null, Modifier.size(size * 0.5f), tint = if (unlocked) color else TextDim.copy(alpha = 0.55f))
    }
}

/** Level, rank, XP towards the next level and the achievement count. Used on both kinds of profile. */
@Composable
fun LevelCard(xp: Int, unlocked: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val level = levelFor(xp)
    val into = xp - xpForLevel(level)
    val span = xpForLevel(level + 1) - xpForLevel(level)
    Card(modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Accent.copy(alpha = 0.14f)).border(2.dp, Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$level", color = Accent, fontWeight = FontWeight.Bold, fontSize = if (level < 100) 22.sp else 17.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Level $level", style = MaterialTheme.typography.titleMedium)
                    Text("  ·  ${rankFor(level)}", style = MaterialTheme.typography.bodyMedium, color = TextBody, maxLines = 1)
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(into / span.toFloat(), Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    "${number(into)} / ${number(span)} XP to level ${level + 1}  ·  $unlocked of ${ACHIEVEMENTS.size} achievements",
                    style = MaterialTheme.typography.labelSmall, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = TextDim)
        }
    }
}

/** A person's most recent achievements as a row of medals. */
@Composable
fun LatestBadges(unlocked: Map<String, Long>, onOpen: () -> Unit) {
    val latest = remember(unlocked) {
        unlocked.entries.sortedByDescending { it.value }.mapNotNull { ACHIEVEMENTS_BY_ID[it.key] }
            .sortedByDescending { it.tier }.take(12)
    }
    if (latest.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text("Achievements", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            items(latest, key = { it.id }) { a ->
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Medal(a, true, 52.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        a.title, style = MaterialTheme.typography.labelSmall, color = TextBody,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Every achievement, grouped, for you or a friend. Your own show how far along each one is; a
 * friend's just show which are unlocked.
 */
@Composable
fun AchievementsScreen(uid: String, onBack: () -> Unit) {
    val social = Graph.social
    val isMe = uid == social.myUid
    val standing by Progression.standing.collectAsStateWithLifecycle()
    val record by social.record.collectAsStateWithLifecycle()
    val profile by produceState<Profile?>(null, uid) {
        if (!isMe) value = runCatching { social.profile(uid) }.getOrNull()
    }
    val unlocked: Map<String, Long> =
        if (isMe) (standing?.unlocked.orEmpty() - record.unlocked.keys).associateWith { 0L } + record.unlocked
        else profile?.unlocked.orEmpty()
    val xp = if (isMe) standing?.xp ?: record.xp else profile?.xp ?: 0
    val stats: Stats? = if (isMe) standing?.stats else null
    var filter by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(
                if (isMe) "Achievements" else profile?.username?.let { "@$it's achievements" } ?: "Achievements",
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { LevelCard(xp, unlocked.size) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Pill("All", filter == 0, { filter = 0 })
                    Pill("Unlocked  ${unlocked.size}", filter == 1, { filter = 1 })
                    Pill("Locked  ${ACHIEVEMENTS.size - unlocked.size}", filter == 2, { filter = 2 })
                }
            }
            Group.entries.forEach { group ->
                val all = ACHIEVEMENTS.filter { it.group == group }
                val shown = all.filter { filter == 0 || (filter == 1) == (it.id in unlocked) }
                if (shown.isEmpty()) return@forEach
                item(key = "h_${group.name}") {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 2.dp, end = 2.dp)) {
                        Text(group.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            "${all.count { it.id in unlocked }} / ${all.size}",
                            style = MaterialTheme.typography.labelMedium, color = TextDim,
                        )
                    }
                }
                items(shown, key = { it.id }) { a -> AchievementRow(a, unlocked[a.id], a.id in unlocked, stats) }
            }
        }
    }
}

@Composable
private fun AchievementRow(a: Achievement, at: Long?, unlocked: Boolean, stats: Stats?) {
    Card(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Medal(a, unlocked)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleSmall, color = if (unlocked) TextHi else TextBody)
                Text(a.text, style = MaterialTheme.typography.bodySmall, color = TextDim)
                Spacer(Modifier.height(6.dp))
                when {
                    unlocked -> Text(
                        listOfNotNull(
                            a.tier.label,
                            "+${a.tier.xp} XP",
                            at?.takeIf { it > 0 }?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall, color = tierColor(a.tier),
                    )
                    stats != null && a.goal > 1 -> {
                        val value = a.value(stats).coerceIn(0, a.goal)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressBar(value / a.goal.toFloat(), Modifier.weight(1f), color = tierColor(a.tier).copy(alpha = 0.8f))
                            Spacer(Modifier.width(10.dp))
                            Text("${number(value)} / ${number(a.goal)}", style = MaterialTheme.typography.labelSmall, color = TextDim)
                        }
                    }
                    else -> Text(
                        "${a.tier.label}  ·  ${a.tier.xp} XP",
                        style = MaterialTheme.typography.labelSmall, color = TextDim,
                    )
                }
            }
        }
    }
}

/** Picks the border round your picture. Locked ones say what unlocks them. */
@Composable
fun BorderDialog(name: String, uid: String, level: Int, achievements: Int, current: Frame, onDismiss: () -> Unit) {
    val social = Graph.social
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Picture border") },
        text = {
            Column {
                Text(
                    "Borders unlock as you level up and collect achievements. Friends see the one you pick.",
                    style = MaterialTheme.typography.bodyMedium, color = TextDim,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 14.dp).height(360.dp),
                ) {
                    items(Frame.entries.toList(), key = { it.key }) { frame ->
                        val open = frame.unlocked(level, achievements)
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = open) { scope.launch { runCatching { social.setFrame(frame) } } }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.alpha(if (open) 1f else 0.38f)) { Avatar(name, uid, 60.dp, frame = frame) }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                frame.label, style = MaterialTheme.typography.labelMedium,
                                color = if (frame == current) Accent else if (open) TextHi else TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    frame == current -> "In use"
                                    open -> "Unlocked"
                                    else -> frame.needs
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (frame == current) Accent else TextDim,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
