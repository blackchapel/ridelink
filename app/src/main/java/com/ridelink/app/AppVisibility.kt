package com.ridelink.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether RideLink is on screen right now. Notifications that exist only to
 * pull the user back to the app (new route, incoming voice request, "just
 * connected") check this and stay quiet while the app is already open.
 *
 * Backed by ProcessLifecycleOwner, which ignores the brief stop/start gap of
 * a rotation, so this doesn't flicker to false and back.
 */
object AppVisibility {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** Call once from Application.onCreate (main thread). */
    fun init() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> _isForeground.value = true
                    Lifecycle.Event.ON_STOP -> _isForeground.value = false
                    else -> Unit
                }
            },
        )
    }
}
