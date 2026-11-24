package com.kumo.anime

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kumo.anime.data.Social
import com.kumo.anime.notify.InboxWorker
import com.kumo.anime.notify.Notifier
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

object Graph {
    lateinit var social: Social
        private set

    fun init(app: Application) {
        social = Social(app)
        social.start()
    }
}

class KumoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannel(this)
        if (Graph.social.enabled) {
            InboxWorker.schedule(this)
            // While the process is alive, new inbox items become system notifications
            // whenever the app isn't on screen. The worker covers the rest.
            MainScope().launch {
                Graph.social.notices.collect { list ->
                    val onScreen = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    Notifier.deliver(this@KumoApp, list, post = !onScreen)
                }
            }
        }
    }
}
