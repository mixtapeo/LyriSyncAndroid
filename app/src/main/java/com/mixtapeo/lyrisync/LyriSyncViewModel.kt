package com.mixtapeo.lyrisync

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LyricSource {
    SPOTIFY,
    MANUAL
}

class LyriSyncViewModel : androidx.lifecycle.ViewModel() {
    private val _activeIndex = kotlinx.coroutines.flow.MutableStateFlow(-1)
    val activeIndex: kotlinx.coroutines.flow.StateFlow<Int> = _activeIndex.asStateFlow()

    private val _source = kotlinx.coroutines.flow.MutableStateFlow(LyricSource.SPOTIFY)
    val source: kotlinx.coroutines.flow.StateFlow<LyricSource> = _source.asStateFlow()

    fun updateActiveIndex(newIndex: Int) {
        if (_activeIndex.value != newIndex) {
            _activeIndex.value = newIndex
        }
    }

    fun setSource(newSource: LyricSource) {
        _source.value = newSource
    }
}