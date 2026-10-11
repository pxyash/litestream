package app.litestream.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class ExtraDef(val name: String = "", val isRequired: Boolean = false)

@Serializable
data class CatalogDef(
    val type: String = "",
    val id: String = "",
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraRequired: List<String> = emptyList(),
    val extraSupported: List<String> = emptyList()
) {
    val searchable: Boolean get() = extra.any { it.name == "search" } || "search" in extraSupported
    val needsExtra: Boolean get() = extra.any { it.isRequired } || extraRequired.isNotEmpty()
}

@Serializable
data class Manifest(
    val id: String = "",
    val name: String = "Addon",
    val version: String = "",
    val description: String? = null,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogDef> = emptyList()
) {
    fun supports(resource: String, type: String): Boolean = resources.any { el ->
        when (el) {
            is JsonPrimitive -> el.content == resource && (types.isEmpty() || type in types)
            is JsonObject -> {
                el["name"]?.jsonPrimitive?.content == resource && run {
                    val t = (el["types"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: types
                    t.isEmpty() || type in t
                }
            }
            else -> false
        }
    }
}

@Serializable
data class Addon(val baseUrl: String, val manifest: Manifest)

@Serializable
data class Video(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    val overview: String? = null
) {
    val ep: Int? get() = episode ?: number
    val label: String get() = title ?: name ?: "Episode ${ep ?: ""}"
}

@Serializable
data class Meta(
    val id: String,
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val year: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val videos: List<Video> = emptyList()
)

@Serializable data class Subtitle(val id: String = "", val url: String = "", val lang: String = "und")

@Serializable
data class BehaviorHints(val videoSize: Long? = null, val filename: String? = null)

@Serializable
data class Stream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val behaviorHints: BehaviorHints? = null
)

@Serializable data class CatalogResponse(val metas: List<Meta> = emptyList())
@Serializable data class MetaResponse(val meta: Meta? = null)
@Serializable data class StreamResponse(val streams: List<Stream> = emptyList())
@Serializable data class SubsResponse(val subtitles: List<Subtitle> = emptyList())

data class CatalogRow(val order: Int, val title: String, val items: List<Meta>)
data class AddonStream(val addonName: String, val stream: Stream)
data class PlaybackRequest(
    val url: String,
    val title: String,
    val mediaKey: String,
    val type: String,
    val id: String,
    val embeddedSubs: List<Subtitle>
)
