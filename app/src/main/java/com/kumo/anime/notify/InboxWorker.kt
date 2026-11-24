package com.kumo.anime.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kumo.anime.BuildConfig
import com.kumo.anime.data.Social
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Checks the inbox every ~15 minutes while the app is closed, so notifications arrive
 * without a paid push server. (Android decides the exact timing.)
 */
class InboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.HAS_FIREBASE) return Result.success()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        return try {
            val snap = FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("notifications")
                .whereGreaterThan("createdAt", Notifier.lastNotified(applicationContext))
                .limit(20).get().await()
            Notifier.deliver(applicationContext, snap.documents.mapNotNull(Social::toNotice), post = true)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("inbox", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
