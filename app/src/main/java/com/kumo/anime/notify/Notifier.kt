package com.kumo.anime.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kumo.anime.MainActivity
import com.kumo.anime.R
import com.kumo.anime.data.Notice
import com.kumo.anime.data.NoticeType

object Notifier {
    private const val CHANNEL = "social"
    private const val UPDATES = "updates"
    private const val UPDATE_ID = 9001
    private const val PREFS = "notifier"
    private const val KEY_LAST = "lastNotified"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Friends and suggestions", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Friend requests, watch-together suggestions and progress updates"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(ch)
        // Its own channel, so people can keep friend activity on and turn update notices off, or the reverse.
        val updates = NotificationChannel(UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        updates.description = "Tells you when a new version of Kumo is ready"
        nm.createNotificationChannel(updates)
    }

    /** "A new version is out", shown once per version. Tapping it opens the Profile tab, where the Update button is. */
    @SuppressLint("MissingPermission")
    fun showUpdate(ctx: Context, version: String): Boolean {
        val nm = NotificationManagerCompat.from(ctx)
        // Not shown counts as not told, so it's tried again once notifications are allowed.
        if (!nm.areNotificationsEnabled()) return false
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PROFILE)
        val pi = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = "Tap to update. Your list stays as it is."
        val notification = NotificationCompat.Builder(ctx, UPDATES)
            .setSmallIcon(R.drawable.ic_stat_kumo)
            .setColor(0xFFB4A5FF.toInt())
            .setContentTitle("Kumo $version is out")
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(UPDATE_ID, notification)
        return true
    }

    fun lastNotified(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, System.currentTimeMillis())

    /** Posts anything newer than the last delivered item (or just records it when [post] is false). */
    @Synchronized
    fun deliver(ctx: Context, notices: List<Notice>, post: Boolean) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST)) {
            // First run: don't replay history as notifications.
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            return
        }
        val last = prefs.getLong(KEY_LAST, 0)
        val fresh = notices.filter { !it.read && it.createdAt > last }
        if (fresh.isEmpty()) return
        if (post) fresh.forEach { show(ctx, it) }
        prefs.edit().putLong(KEY_LAST, fresh.maxOf { it.createdAt }).apply()
    }

    fun text(n: Notice): Pair<String, String> {
        val who = "@${n.fromName}"
        val title = n.title ?: "an anime"
        return when (n.type) {
            NoticeType.FRIEND_REQUEST -> who to "sent you a friend request"
            NoticeType.FRIEND_ACCEPTED -> who to "accepted your friend request"
            NoticeType.SUGGESTION -> who to "wants to watch $title with you" + (n.message?.let { " · \"$it\"" } ?: "")
            NoticeType.SUGGESTION_REPLY ->
                who to if (n.accepted == true) "is in for $title" else "passed on $title for now"
            NoticeType.CAUGHT_UP -> who to "caught up to you on $title" + (n.message?.let { " ($it)" } ?: "")
            NoticeType.RECOMMENDATION -> who to "recommends $title" + (n.message?.let { " · \"$it\"" } ?: "")
            else -> who to "sent you something"
        }
    }

    @SuppressLint("MissingPermission")
    private fun show(ctx: Context, n: Notice) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val (who, body) = text(n)
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_INBOX)
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kumo)
            .setColor(0xFFB4A5FF.toInt())
            .setContentTitle(who)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(n.id.hashCode(), notification)
    }
}
