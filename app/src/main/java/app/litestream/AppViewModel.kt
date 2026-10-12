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
import kotlinx.coroutines.withTimeoutOrNull

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
    val searchStatus = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null

    val installStatus = MutableStateFlow<String?>(null)

    var sortMode by mutableStateOf(repo.sortMode)
        private set

    var playback by mutableStateOf<PlaybackRequest?>(null)

    /** Last opened detail page; used to enrich Continue Watching entries. */
    var currentMeta: Meta? = null

    init {
        viewModelScope.launch { repo.ensureDefaults() }
    }

    fun setSort(mode: String) {
        sortMode = mode
        repo.sortMode = mode
    }

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
                                val title = "${c.name ?: c.id} · ${c.type.replaceFirstChar { it.uppercase() }}"
                                val row = CatalogRow(idx, title, items.take(30))
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
        if (q.isBlank()) {
            _search.value = emptyList()
            searchStatus.value = null
            return
        }
        searchJob = viewModelScope.launch {
            _search.value = emptyList()
            searchStatus.value = "Searching…"
            val report = mutableListOf<String>()
            var attempted = 0
            var order = 0
            coroutineScope {
                repo.addons.value.forEach { a ->
                    a.manifest.catalogs.filter { it.searchable }.forEach { c ->
                        val idx = order++
                        attempted++
                        launch {
                            val label = "${a.manifest.name} / ${c.name ?: c.id} (${c.type})"
                            runCatching { repo.catalog(a, c, q.trim()) }
                                .onSuccess { items ->
                                    report += "$label: ${items.size}"
                                    if (items.isNotEmpty()) {
                                        val title = "${a.manifest.name} · ${c.type.replaceFirstChar { it.uppercase() }}"
                                        val row = CatalogRow(idx, title, items)
                                        _search.update { (it + row).sortedBy { r -> r.order } }
                                    }
                                }
                                .onFailure { report += "$label: ${it.message ?: it.javaClass.simpleName}" }
                        }
                    }
                }
            }
            val total = _search.value.sumOf { it.items.size }
            searchStatus.value = when {
                attempted == 0 ->
                    "None of your installed addons declare a search catalog (" +
                        repo.addons.value.joinToString { it.manifest.name } + ")."
                total == 0 -> "No results for \"${q.trim()}\".\n" + report.joinToString("\n")
                else -> "Searched: " + report.joinToString("  ·  ")
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

    /** Auto-picks a source for the next episode (same provider + quality as the current one if possible). */
    fun playNext(cur: PlaybackRequest, next: Video, onNoStream: () -> Unit) {
        viewModelScope.launch {
            var best: ParsedStream? = null
            withTimeoutOrNull(20_000) {
                repo.streams("series", next.id).collect { all ->
                    val parsed = sortStreams(parseStreams(all), sortMode)
                    best = parsed.firstOrNull { it.addon == cur.addon && it.quality == cur.quality }
                        ?: parsed.firstOrNull { it.addon == cur.addon }
                        ?: parsed.firstOrNull()
                }
            }
            val b = best
            if (b == null) {
                onNoStream()
            } else {
                playback = PlaybackRequest(
                    url = b.url, title = cur.title, subtitle = next.tag(), type = "series", id = next.id,
                    metaId = cur.metaId, thumb = next.thumbnail ?: cur.thumb, addon = b.addon,
                    quality = b.quality, streamInfo = b.text, embeddedSubs = b.stream.subtitles
                )
            }
        }
    }
}
