package app.litestream.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Official Stremio metadata/catalog addon, installed on first launch (removable in Settings). */
const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

class AddonRepository(context: Context) {
    private val prefs = context.getSharedPreferences("litestream", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    private val _addons = MutableStateFlow(loadAddons())
    val addons: StateFlow<List<Addon>> = _addons.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<WatchEntry>> = _history.asStateFlow()

    var sortMode: String
        get() = prefs.getString("sort", "quality") ?: "quality"
        set(v) { prefs.edit().putString("sort", v).apply() }

    // ---------- storage ----------
    private fun loadAddons(): List<Addon> {
        val raw = prefs.getString("addons", null) ?: return emptyList()
        return runCatching { AppJson.decodeFromString<List<Addon>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveAddons(list: List<Addon>) {
        _addons.value = list
        prefs.edit().putString("addons", AppJson.encodeToString(list)).apply()
    }

    private fun loadHistory(): List<WatchEntry> {
        val raw = prefs.getString("history", null) ?: return emptyList()
        return runCatching { AppJson.decodeFromString<List<WatchEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveHistory(list: List<WatchEntry>) {
        _history.value = list
        prefs.edit().putString("history", AppJson.encodeToString(list)).apply()
    }

    fun resumePosition(videoId: String): Long =
        _history.value.firstOrNull { it.videoId == videoId }?.positionMs ?: 0L

    fun upsertHistory(e: WatchEntry) {
        val list = listOf(e.copy(updatedAt = System.currentTimeMillis())) +
            _history.value.filter { it.videoId != e.videoId }
        saveHistory(list.take(100))
    }

    fun removeHistory(videoId: String) {
        if (_history.value.any { it.videoId == videoId }) saveHistory(_history.value.filter { it.videoId != videoId })
    }

    fun clearHistory() = saveHistory(emptyList())

    // ---------- network ----------
    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "LiteStream/1.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body!!.string()
        }
    }

    /** Decodes each array element on its own so one malformed item doesn't drop the whole list. */
    private fun <T> parseArray(body: String, key: String, ser: KSerializer<T>): List<T> {
        val arr = AppJson.parseToJsonElement(body).jsonObject[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { runCatching { AppJson.decodeFromJsonElement(ser, it) }.getOrNull() }
    }

    private fun parseMeta(body: String): Meta? {
        val obj = AppJson.parseToJsonElement(body).jsonObject["meta"] as? JsonObject ?: return null
        val base = AppJson.decodeFromJsonElement(Meta.serializer(), JsonObject(obj - "videos"))
        val vids = (obj["videos"] as? JsonArray)?.mapNotNull {
            runCatching { AppJson.decodeFromJsonElement(Video.serializer(), it) }.getOrNull()
        } ?: emptyList()
        return base.copy(videos = vids)
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
        saveAddons(_addons.value.filter { it.baseUrl != base } + addon)
        addon
    }

    fun remove(baseUrl: String) = saveAddons(_addons.value.filter { it.baseUrl != baseUrl })

    suspend fun catalog(addon: Addon, cat: CatalogDef, search: String? = null): List<Meta> {
        val id = Uri.encode(cat.id)
        val path = if (search != null) "catalog/${cat.type}/$id/search=${Uri.encode(search)}.json"
        else "catalog/${cat.type}/$id.json"
        return parseMetas(fetch("${addon.baseUrl}/$path"), cat.type)
    }

    suspend fun meta(type: String, id: String): Meta? {
        for (a in _addons.value.filter { it.manifest.supports("meta", type) }) {
            val m = runCatching {
                parseMetaDetail(fetch("${a.baseUrl}/meta/$type/${Uri.encode(id, ":")}.json"))
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
                    parseStreamList(fetch("${a.baseUrl}/stream/$type/${Uri.encode(id, ":")}.json"))
                        .map { AddonStream(a.manifest.name, it) }
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
                        parseSubtitleList(fetch("${a.baseUrl}/subtitles/$type/${Uri.encode(id, ":")}.json"))
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }
}
