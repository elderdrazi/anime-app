package com.kumo.anime.notify

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kumo.anime.BuildConfig
import com.kumo.anime.data.Social
import com.kumo.anime.data.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The background check: about every 15 minutes while the app is closed it looks for new inbox
 * items and, a few times a day, for a new version, so both can arrive as notifications without a
 * paid push server. (Android decides the exact timing.)
 *
 * It's a plain JobScheduler job on purpose. From Android 15, an app in the background only gets
 * network access while the system is running one of its jobs, so the check has to be started by
 * the system rather than by a timer inside the app.
 */
class CheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { checkForUpdate(applicationContext) }
            val retry = runCatching { checkInbox(applicationContext) }.isFailure
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    /** Switching between Wi-Fi and mobile data mid-check needs no special handling. */
    override fun onNetworkChanged(params: JobParameters) {}

    companion object {
        private const val TAG = "CheckJob"
        private const val JOB_ID = 4100
        private const val EVERY = 15L * 60 * 1000
        private const val UPDATE_CHECK_EVERY = 6L * 60 * 60 * 1000

        fun schedule(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java)
            // Already set up: leave it, or every launch would restart the 15-minute clock.
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, CheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(EVERY)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        private suspend fun checkInbox(ctx: Context) {
            if (!BuildConfig.HAS_FIREBASE) return
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val snap = FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("notifications")
                .whereGreaterThan("createdAt", Notifier.lastNotified(ctx))
                .limit(20).get().await()
            Notifier.deliver(ctx, snap.documents.mapNotNull(Social::toNotice), post = true)
        }

        /** Looks for a new version a few times a day and says so once per version. */
        private suspend fun checkForUpdate(ctx: Context) {
            val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("checkedAt", 0) < UPDATE_CHECK_EVERY) return
            val update = runCatching { Updater.newer() }.getOrElse {
                // Not recorded as checked, so the next run tries again.
                Log.w(TAG, "update check failed", it)
                return
            }
            prefs.edit().putLong("checkedAt", now).apply()
            if (update == null || prefs.getString("notified", null) == update.version) return
            if (Notifier.showUpdate(ctx, update.version)) {
                prefs.edit().putString("notified", update.version).apply()
            }
        }
    }
}
