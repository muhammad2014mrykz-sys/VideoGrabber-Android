package com.videograbber.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared, process-wide download state so the UI and the service stay in sync. */
object DownloadBus {

    sealed interface State {
        data object Idle : State
        data object Preparing : State
        data class Running(val percent: Float, val line: String) : State
        data class Success(val savedPath: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun update(s: State) {
        _state.value = s
    }

    fun reset() {
        _state.value = State.Idle
    }
}
