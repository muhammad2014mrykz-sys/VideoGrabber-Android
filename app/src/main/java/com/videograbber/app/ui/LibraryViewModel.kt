package com.videograbber.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videograbber.app.core.DownloadBus
import com.videograbber.app.core.DownloadItem
import com.videograbber.app.core.DownloadsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        // Auto-refresh the library whenever a download finishes.
        viewModelScope.launch {
            DownloadBus.state.collect {
                if (it is DownloadBus.State.Success) refresh()
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _items.value = DownloadsRepository.list(getApplication())
            _loading.value = false
        }
    }

    fun delete(item: DownloadItem) {
        viewModelScope.launch {
            DownloadsRepository.delete(getApplication(), item)
            refresh()
        }
    }
}
