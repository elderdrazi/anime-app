package com.kumo.anime.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val version: String, val url: String) : UpdateState
    data class Downloading(val update: Available, val fraction: Float) : UpdateState
    data class Failed(val message: String, val update: Available?) : UpdateState
}

/**
 * The Play Store version never updates itself: Google Play delivers updates, and its rules forbid
 * an app from installing its own. This stands in for the GitHub version's updater and does nothing,
 * so no update is ever offered.
 */
object Updater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check(loud: Boolean = false) {}

    suspend fun newer(): UpdateState.Available? = null

    suspend fun install(ctx: Context, update: UpdateState.Available) {}
}
