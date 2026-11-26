package com.kumo.anime.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kumo.anime.ui.theme.Bg
import com.kumo.anime.ui.theme.Surface1
import com.kumo.anime.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Side of a saved profile picture, in pixels. Big enough for the largest avatar, small enough to load instantly. */
private const val PHOTO_SIDE = 256
private const val MAX_ZOOM = 6f

/**
 * Full-screen "move and zoom" step after picking a photo. Whatever sits inside the circle becomes
 * the profile picture. [onUse] gets the finished square JPEG and may throw with a message to show.
 */
@Composable
fun PhotoCropDialog(uri: Uri, onDismiss: () -> Unit, onUse: suspend (ByteArray) -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val loaded by produceState<Result<Bitmap>?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadPicked(ctx, uri) } }
    }
    val bitmap = loaded?.getOrNull()
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val scope = rememberCoroutineScope()
    var side by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Move and zoom", style = MaterialTheme.typography.headlineSmall)
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Surface1)
                    .onSizeChanged { side = it.width.toFloat() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loaded == null -> CircularProgressIndicator()
                    bitmap == null || image == null -> Text(
                        "That picture couldn't be opened. Try another one.",
                        color = TextDim, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
                    )
                    else -> Canvas(
                        Modifier.fillMaxSize().pointerInput(bitmap) {
                            detectTransformGestures { _, pan, change, _ ->
                                zoom = (zoom * change).coerceIn(1f, MAX_ZOOM)
                                offset = clampOffset(offset + pan, bitmap, side, zoom)
                            }
                        },
                    ) {
                        val scale = side / min(bitmap.width, bitmap.height) * zoom
                        val w = bitmap.width * scale
                        val h = bitmap.height * scale
                        drawImage(
                            image,
                            dstOffset = IntOffset(((side - w) / 2 + offset.x).roundToInt(), ((side - h) / 2 + offset.y).roundToInt()),
                            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                        // Dim everything outside the circle that will be kept.
                        val circle = Path().apply { addOval(Rect(0f, 0f, side, side)) }
                        clipPath(circle, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.62f)) }
                        drawCircle(Color.White.copy(alpha = 0.8f), radius = side / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
            Text(
                error ?: "Drag to move, pinch to zoom. Friends see what's inside the circle.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else TextDim,
            )
            // Kept right under the picture rather than at the foot of the screen, clear of the system bars.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss, enabled = !busy,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val source = bitmap ?: return@Button
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val jpeg = withContext(Dispatchers.Default) { cropToJpeg(source, side, zoom, offset) }
                                onUse(jpeg)
                            } catch (e: Exception) {
                                error = e.message ?: "Couldn't save your picture. Try again."
                            }
                            busy = false
                        }
                    },
                    enabled = bitmap != null && side > 0 && !busy,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Use picture")
                }
            }
        }
    }
}

/** Keeps the picture covering the whole frame, so the circle never shows an empty edge. */
private fun clampOffset(o: Offset, bitmap: Bitmap, side: Float, zoom: Float): Offset {
    if (side <= 0f) return Offset.Zero
    val scale = side / min(bitmap.width, bitmap.height) * zoom
    val maxX = max(0f, (bitmap.width * scale - side) / 2)
    val maxY = max(0f, (bitmap.height * scale - side) / 2)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/** Cuts out the square that's showing in the frame and shrinks it to a small JPEG. */
private fun cropToJpeg(bitmap: Bitmap, side: Float, zoom: Float, offset: Offset): ByteArray {
    val scale = side / min(bitmap.width, bitmap.height) * zoom
    val size = (side / scale).roundToInt().coerceIn(1, min(bitmap.width, bitmap.height))
    val left = ((bitmap.width - side / scale) / 2 - offset.x / scale).roundToInt().coerceIn(0, bitmap.width - size)
    val top = ((bitmap.height - side / scale) / 2 - offset.y / scale).roundToInt().coerceIn(0, bitmap.height - size)
    val square = Bitmap.createBitmap(bitmap, left, top, size, size)
    val small = Bitmap.createScaledBitmap(square, PHOTO_SIDE, PHOTO_SIDE, true)
    return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

/** Opens a picked photo at a working size (long edge around 1600px), the right way up. */
private fun loadPicked(ctx: Context, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder turns photos the right way up from the camera's rotation tag.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 1600))
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    val options = BitmapFactory.Options().apply { inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / 1600) }
    return ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Couldn't read the picture")
}
