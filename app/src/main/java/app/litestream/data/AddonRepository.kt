package app.litestream.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Official Stremio metadata/catalog addon, installed on first launch (removable in Settings). */
const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

class AddonRepository(context: Context) {
    private val prefs = context.getSharedPreferences("litestream", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    private val _addons = MutableStateFlow(load())
    val addons: StateFlow<List<Addon>> = _addons.asStateFlow()

    var sortMode: String
        get() = prefs.getString("sort", "quality") ?: "quality"
        set(v) { prefs.edit().putString("sort", v).apply() }

    fun position(key: String): Long = prefs.getLong("pos_$key", 0L)
    fun savePosition(key: String, ms: Long) { prefs.edit().putLong("pos_$key", ms).apply() }

    private fun load(): List<Addon> {
        val raw = prefs.getString("addons", null) ?: return emptyList()
        return runCatching { AppJson.decodeFromString<List<Addon>>(raw) }.getOrDefault(emptyList())
    }

    private fun save(list: List<Addon>) {
        _addons.value = list
        prefs.edit().putString("addons", AppJson.encodeToString(list)).apply()
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "LiteStream/1.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body!!.string()
        }
    }

    suspend fun ensureDefaults() {
        if (prefs.getBoolean("defaults_done", false)) return
        install(CINEMETA).onSuccess { prefs.edit().putBoolean("defaults_done", true).apply() }
    }

    suspend fun install(input: String): Result<Addon> = runCatching {
        val u = input.trim().replace("stremio://", "https://")
        require(u.startsWith("http")) { "Enter a valid addon URL" }
        val base = u.removeSuffix("/manifest.json").removeSuffix("/configure").trimEnd('/')
        val manifest = AppJson.decodeFromString<Manifest>(fetch("$base/manifest.json"))
        val addon = Addon(base, manifest)
        save(_addons.value.filter { it.baseUrl != base } + addon)
        addon
    }

    fun remove(baseUrl: String) = save(_addons.value.filter { it.baseUrl != baseUrl })

    suspend fun catalog(addon: Addon, cat: CatalogDef, search: String? = null): List<Meta> {
        val id = Uri.encode(cat.id)
        val path = if (search != null) "catalog/${cat.type}/$id/search=${Uri.encode(search)}.json"
        else "catalog/${cat.type}/$id.json"
        return AppJson.decodeFromString<CatalogResponse>(fetch("${addon.baseUrl}/$path")).metas
            .map { if (it.type.isEmpty()) it.copy(type = cat.type) else it }
    }

    suspend fun meta(type: String, id: String): Meta? {
        for (a in _addons.value.filter { it.manifest.supports("meta", type) }) {
            val m = runCatching {
                AppJson.decodeFromString<MetaResponse>(
                    fetch("${a.baseUrl}/meta/$type/${Uri.encode(id, ":")}.json")
                ).meta
            }.getOrNull()
            if (m != null) return m.copy(type = m.type.ifEmpty { type })
        }
        return null
    }

    /** Emits a growing list as each addon answers, so fast addons show up immediately. */
    fun streams(type: String, id: String): Flow<List<AddonStream>> = channelFlow {
        val acc = mutableListOf<AddonStream>()
        val lock = Mutex()
        _addons.value.filter { it.manifest.supports("stream", type) }.forEach { a ->
            launch {
                val r = runCatching {
                    AppJson.decodeFromString<StreamResponse>(
                        fetch("${a.baseUrl}/stream/$type/${Uri.encode(id, ":")}.json")
                    ).streams.map { AddonStream(a.manifest.name, it) }
                }.getOrDefault(emptyList())
                lock.withLock { acc += r; send(acc.toList()) }
            }
        }
    }

    suspend fun subtitles(type: String, id: String): List<Subtitle> = coroutineScope {
        _addons.value.filter { it.manifest.supports("subtitles", type) }.map { a ->
            async {
                runCatching {
                    withTimeout(6000) {
                        AppJson.decodeFromString<SubsResponse>(
                            fetch("${a.baseUrl}/subtitles/$type/${Uri.encode(id, ":")}.json")
                        ).subtitles
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }
}
