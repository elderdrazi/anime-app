package com.kumo.anime.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kumo.anime.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val version: String, val url: String) : UpdateState
    data class Downloading(val update: Available, val fraction: Float) : UpdateState
    data class Failed(val message: String, val update: Available?) : UpdateState
}

/**
 * Kumo isn't on the Play Store, so it updates itself: it looks at the newest release on GitHub,
 * downloads that APK and hands it to Android's installer. Android only accepts it if it's signed
 * with the same key as the installed app, so a tampered download can't replace it.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/elderdrazi/anime-app/releases/latest"
    private const val ASSET = "kumo.apk"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Looks for a newer release. Quiet on failure unless [loud], since it also runs at every launch. */
    suspend fun check(loud: Boolean = false) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Checking) return
        val before = _state.value
        _state.value = UpdateState.Checking
        _state.value = try {
            newer() ?: UpdateState.Current
        } catch (e: Exception) {
            if (loud) UpdateState.Failed("Couldn't check for updates. Try again in a bit.", null) else before
        }
    }

    /** The newest release if it's ahead of the installed version, or null if this one is current. Throws when GitHub can't be reached. */
    suspend fun newer(): UpdateState.Available? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GitHub answered ${r.code}")
            val release = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            val version = release["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: error("No version")
            val url = release["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content == ASSET }
                ?.get("browser_download_url")?.jsonPrimitive?.content
            if (url != null && isNewer(version, BuildConfig.VERSION_NAME)) UpdateState.Available(version, url) else null
        }
    }

    /** Downloads the release and opens Android's install prompt. */
    suspend fun install(ctx: Context, update: UpdateState.Available) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(update, 0f)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "kumo-${update.version}.apk")
                http.newCall(Request.Builder().url(update.url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("Download answered ${r.code}")
                    val body = r.body ?: error("Empty download")
                    val total = body.contentLength()
                    var done = 0L
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (total > 0) _state.value = UpdateState.Downloading(update, done / total.toFloat())
                            }
                        }
                    }
                }
                out
            }
            // Back to "available" so the button still works if the install prompt is dismissed.
            _state.value = update
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("The update didn't download. Check your connection and try again.", update)
        }
    }

    /** Compares dotted versions number by number, so 1.10 is newer than 1.9. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
