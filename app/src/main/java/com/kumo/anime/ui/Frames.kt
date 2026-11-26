package com.kumo.anime.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.kumo.anime.data.Frame

/** The colours a border sweeps through, going once round the picture. Empty for no border. */
fun frameColors(frame: Frame): List<Color> = when (frame) {
    Frame.NONE -> emptyList()
    Frame.BRONZE -> listOf(0xFFE8AE7E, 0xFF94552B, 0xFFE8AE7E, 0xFFB87444, 0xFFE8AE7E)
    Frame.SILVER -> listOf(0xFFF4F6FA, 0xFF858D9E, 0xFFF4F6FA, 0xFFAAB2C2, 0xFFF4F6FA)
    Frame.GOLD -> listOf(0xFFFFE694, 0xFFC98A12, 0xFFFFE694, 0xFFE5AE35, 0xFFFFE694)
    Frame.SAKURA -> listOf(0xFFFFC6DF, 0xFFFF6AA6, 0xFFFFE8F2, 0xFFFF8FBD, 0xFFFFC6DF)
    Frame.OCEAN -> listOf(0xFF5EEAD4, 0xFF2563EB, 0xFF7DD3FC, 0xFF0EA5E9, 0xFF5EEAD4)
    Frame.EMBER -> listOf(0xFFFFD166, 0xFFFF7A1A, 0xFFE11D48, 0xFFFF9F1C, 0xFFFFD166)
    Frame.AURORA -> listOf(0xFF6EE7B7, 0xFF22D3EE, 0xFFA78BFA, 0xFF34D399, 0xFF6EE7B7)
    Frame.GALAXY -> listOf(0xFF4338CA, 0xFFD946EF, 0xFF60A5FA, 0xFF7C3AED, 0xFF4338CA)
    Frame.PRISM -> listOf(0xFFFF5F6D, 0xFFFFC371, 0xFF7CFFCB, 0xFF4FACFE, 0xFFB388FF, 0xFFFF5F6D)
    Frame.LEGEND -> listOf(0xFFFFE694, 0xFFFFFFFF, 0xFFE5AE35, 0xFFFFF4C2, 0xFFC98A12, 0xFFFFE694)
    Frame.TROPHY -> listOf(0xFFB9F3FF, 0xFF38BDF8, 0xFFECFDFF, 0xFF67E8F9, 0xFFB9F3FF)
    Frame.CROWN -> listOf(0xFFFFE694, 0xFFB4A5FF, 0xFFFFFFFF, 0xFFFF8FBD, 0xFFFFE694)
}.map { Color(it) }

/** The rarest borders slowly turn. */
val Frame.spins: Boolean get() = this == Frame.PRISM || this == Frame.LEGEND || this == Frame.CROWN

/** Draws a border as a ring of the given [width] just inside the edge of whatever it's applied to. */
@Composable
fun Modifier.frameRing(frame: Frame, width: Dp): Modifier {
    val colors = frameColors(frame)
    if (colors.isEmpty()) return this
    val turn = if (frame.spins) {
        val angle by rememberInfiniteTransition(label = "frame").animateFloat(
            0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
        )
        angle
    } else 0f
    // A filled disc with its middle cut out, rather than a stroked circle: stroking leaves a hairline
    // gap where the circle's outline starts and ends.
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawBehind {
        val outer = size.minDimension / 2
        rotate(turn) {
            drawCircle(Brush.sweepGradient(colors), radius = outer)
            // Sweep gradients leave a one-pixel line of the wrong colour where they wrap round, at
            // three o'clock. The gradient starts and ends on the same colour, so painting that
            // colour over the line hides it.
            drawLine(colors.first(), center, Offset(center.x + outer - 0.5f, center.y), strokeWidth = 2f)
        }
        drawCircle(Color.Black, radius = outer - width.toPx(), blendMode = BlendMode.Clear)
    }
}
