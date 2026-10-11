package app.litestream

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.litestream.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = AddonRepository(app)
    val addons = repo.addons

    private val _home = MutableStateFlow<List<CatalogRow>>(emptyList())
    val home: StateFlow<List<CatalogRow>> = _home.asStateFlow()
    private val _homeLoading = MutableStateFlow(true)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()
    private var homeJob: Job? = null

    private val _search = MutableStateFlow<List<CatalogRow>>(emptyList())
    val searchResults: StateFlow<List<CatalogRow>> = _search.asStateFlow()
    private var searchJob: Job? = null

    val installStatus = MutableStateFlow<String?>(null)

    var sortMode by mutableStateOf(repo.sortMode)
        private set

    var playback by mutableStateOf<PlaybackRequest?>(null)

    init {
        viewModelScope.launch { repo.ensureDefaults() }
    }

    fun setSort(mode: String) {
        sortMode = mode
        repo.sortMode = mode
    }

    private fun title(a: Addon, c: CatalogDef) =
        "${c.name ?: c.id} · ${c.type.replaceFirstChar { it.uppercase() }}"

    fun loadHome() {
        homeJob?.cancel()
        _home.value = emptyList()
        _homeLoading.value = true
        homeJob = viewModelScope.launch {
            var order = 0
            coroutineScope {
                repo.addons.value.forEach { a ->
                    a.manifest.catalogs.filter { !it.needsExtra }.forEach { c ->
                        val idx = order++
                        launch {
                            val items = runCatching { repo.catalog(a, c) }.getOrDefault(emptyList())
                            if (items.isNotEmpty()) {
                                val row = CatalogRow(idx, title(a, c), items.take(30))
                                _home.update { (it + row).sortedBy { r -> r.order } }
                            }
                        }
                    }
                }
            }
            _homeLoading.value = false
        }
    }

    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { _search.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            _search.value = emptyList()
            var order = 0
            coroutineScope {
                repo.addons.value.forEach { a ->
                    a.manifest.catalogs.filter { it.searchable }.forEach { c ->
                        val idx = order++
                        launch {
                            val items = runCatching { repo.catalog(a, c, q.trim()) }.getOrDefault(emptyList())
                            if (items.isNotEmpty()) {
                                val row = CatalogRow(idx, "${a.manifest.name} · ${c.type.replaceFirstChar { it.uppercase() }}", items)
                                _search.update { (it + row).sortedBy { r -> r.order } }
                            }
                        }
                    }
                }
            }
        }
    }

    fun install(url: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            installStatus.value = "Installing…"
            repo.install(url)
                .onSuccess { installStatus.value = "Installed ${it.manifest.name}"; onSuccess() }
                .onFailure { installStatus.value = "Failed: ${it.message ?: "invalid addon"}" }
        }
    }
}
