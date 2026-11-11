package com.example.pocketmuseprototype.catalog

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class LoadStatus {
    Idle, Loading, Ready, MoreAvailable, Error, End
}

internal data class MarketState(
    val models: List<CatalogCheckpoint> = emptyList(),
    val status: LoadStatus = LoadStatus.Idle,
    val loadedInitialBatch: Boolean = false
)

internal class MarketViewModel(private val repository: CatalogRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MarketState())
    val state = mutableState.asStateFlow()

    @MainThread
    fun start() {
        if (state.value.status == LoadStatus.Idle) load()
    }

    @MainThread
    fun prefetch() {
        if (state.value.status == LoadStatus.Ready) load()
    }

    @MainThread
    fun loadMore() {
        when (state.value.status) {
            LoadStatus.Ready, LoadStatus.MoreAvailable, LoadStatus.Error -> load()
            else -> Unit
        }
    }

    private fun load() {
        val previous = state.value
        mutableState.value = previous.copy(status = LoadStatus.Loading)
        viewModelScope.launch {
            try {
                val batch = repository.loadBatch()
                val status = when {
                    !batch.hasMore -> LoadStatus.End
                    batch.models.size < CATALOG_BATCH_SIZE -> LoadStatus.MoreAvailable
                    else -> LoadStatus.Ready
                }
                mutableState.value = MarketState(previous.models + batch.models, status, loadedInitialBatch = true)
            } catch (_: IOException) {
                mutableState.value = previous.copy(status = LoadStatus.Error)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { MarketViewModel(CatalogRepository(CivitaiApi()::models)) }
        }
    }
}
