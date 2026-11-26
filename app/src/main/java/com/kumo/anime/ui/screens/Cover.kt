package com.kumo.anime.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kumo.anime.data.AniList
import com.kumo.anime.data.Entry
import com.kumo.anime.ui.theme.Bg
import com.kumo.anime.ui.theme.Surface1
import kotlinx.coroutines.delay

private class Slide(val entry: Entry, val url: String)

/**
 * A cover photo made from someone's top-rated shows: the banner art of their favourites, fading
 * from one to the next and drifting slowly, darkened at the bottom so a name can sit over it.
 * [top] is their rated shows, best first. Draws nothing until there's art to show.
 */
@Composable
fun CoverPhoto(top: List<Entry>, modifier: Modifier = Modifier) {
    val best = remember(top) { top.take(5) }
    val ids = remember(best) { best.map { it.animeId } }
    val banners by produceState<Map<Int, String>?>(null, ids) {
        value = runCatching { AniList.banners(ids) }.getOrDefault(emptyMap())
    }
    // Wide banner art where a show has it. If none of the favourites do, the top one's poster stands in.
    val slides = remember(best, banners) {
        val known = banners ?: return@remember emptyList()
        best.mapNotNull { e -> known[e.animeId]?.let { Slide(e, it) } }
            .ifEmpty { best.firstOrNull()?.let { e -> e.cover?.let { listOf(Slide(e, it)) } }.orEmpty() }
    }
    var index by remember(slides.size) { mutableIntStateOf(0) }
    LaunchedEffect(slides.size) {
        while (slides.size > 1) {
            delay(7000)
            index = (index + 1) % slides.size
        }
    }
    // A slow drift in and out, so the cover never sits completely still.
    val zoom by rememberInfiniteTransition(label = "cover").animateFloat(
        1f, 1.08f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom",
    )
    val ctx = LocalContext.current
    Box(modifier.background(Surface1)) {
        val current = slides.getOrNull(index)
        Crossfade(current, animationSpec = tween(1100), label = "slide") { slide ->
            if (slide != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(slide.url).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.08f), 0.35f to Bg.copy(alpha = 0.28f),
                    0.68f to Bg.copy(alpha = 0.80f), 1f to Bg.copy(alpha = 0.97f),
                ),
            ),
        )
        if (current != null) {
            // Says which favourite the art is from.
            Text(
                "★ ${current.entry.rating}  ${current.entry.title}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).widthIn(max = 210.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
