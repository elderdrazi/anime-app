package com.kumo.anime

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kumo.anime.data.Social
import com.kumo.anime.notify.CheckJob
import com.kumo.anime.notify.Notifier
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

object Graph {
    lateinit var social: Social
        private set

    fun init(app: Application) {
        com.kumo.anime.data.AniList.init(app)
        com.kumo.anime.data.AniListSync.init(app)
        com.kumo.anime.data.ShowInfo.init(app)
        social = Social(app)
        social.start()
        com.kumo.anime.data.Progression.start(social)
    }
}

class KumoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannel(this)
        // The background check covers new versions as well as the inbox, so it runs either way.
        CheckJob.schedule(this)
        if (Graph.social.enabled) {
            // While the process is alive, new inbox items become system notifications
            // whenever the app isn't on screen. The background check covers the rest.
            MainScope().launch {
                Graph.social.notices.collect { list ->
                    val onScreen = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    Notifier.deliver(this@KumoApp, list, post = !onScreen)
                }
            }
        }
    }
}
