package app.litestream.data

import kotlinx.serialization.json.*

// Hand-written tolerant parsing: addons are wildly inconsistent about field shapes
// (string vs array, number vs string, null vs missing). One odd field must never drop a result.

private fun JsonElement?.str(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.takeIf { it.isNotBlank() }
}

private fun JsonElement?.num(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.toDoubleOrNull()
}

private fun JsonElement?.strList(): List<String> = when (this) {
    is JsonArray -> this.mapNotNull { e -> e.str() ?: (e as? JsonObject)?.get("name").str() }
    is JsonNull -> emptyList()
    is JsonPrimitive -> content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

private fun parseVideo(o: JsonObject): Video? {
    val id = o["id"].str() ?: return null
    return Video(
        id = id,
        title = o["title"].str(),
        name = o["name"].str(),
        season = o["season"].num()?.toInt(),
        episode = o["episode"].num()?.toInt(),
        number = o["number"].num()?.toInt(),
        thumbnail = o["thumbnail"].str(),
        released = o["released"].str(),
        overview = o["overview"].str() ?: o["description"].str()
    )
}

private fun parseMetaObj(o: JsonObject, defaultType: String): Meta? {
    val id = o["id"].str() ?: o["imdb_id"].str() ?: return null
    return Meta(
        id = id,
        type = o["type"].str() ?: defaultType,
        name = o["name"].str() ?: o["title"].str() ?: "",
        poster = o["poster"].str(),
        background = o["background"].str() ?: o["fanart"].str(),
        description = o["description"].str() ?: o["overview"].str(),
        releaseInfo = o["releaseInfo"].str(),
        year = o["year"].str(),
        imdbRating = o["imdbRating"].str(),
        runtime = o["runtime"].str(),
        genres = o["genres"].strList().ifEmpty { o["genre"].strList() },
        cast = o["cast"].strList(),
        director = o["director"].strList(),
        videos = (o["videos"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseVideo) } ?: emptyList()
    )
}

private fun parseSubtitle(o: JsonObject): Subtitle? {
    val url = o["url"].str() ?: return null
    return Subtitle(o["id"].str() ?: "", url, o["lang"].str() ?: "und")
}

private fun parseStreamObj(o: JsonObject): Stream {
    val hints = o["behaviorHints"] as? JsonObject
    return Stream(
        name = o["name"].str(),
        title = o["title"].str(),
        description = o["description"].str(),
        url = o["url"].str(),
        subtitles = (o["subtitles"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseSubtitle) } ?: emptyList(),
        behaviorHints = hints?.let { BehaviorHints(it["videoSize"].num()?.toLong(), it["filename"].str()) }
    )
}

private fun rootArray(body: String, key: String): JsonArray? =
    (AppJson.parseToJsonElement(body) as? JsonObject)?.get(key) as? JsonArray

fun parseMetas(body: String, defaultType: String): List<Meta> =
    rootArray(body, "metas")?.mapNotNull { (it as? JsonObject)?.let { o -> parseMetaObj(o, defaultType) } } ?: emptyList()

fun parseMetaDetail(body: String): Meta? {
    val o = (AppJson.parseToJsonElement(body) as? JsonObject)?.get("meta") as? JsonObject ?: return null
    return parseMetaObj(o, "")
}

fun parseStreamList(body: String): List<Stream> =
    rootArray(body, "streams")?.mapNotNull { (it as? JsonObject)?.let(::parseStreamObj) } ?: emptyList()

fun parseSubtitleList(body: String): List<Subtitle> =
    rootArray(body, "subtitles")?.mapNotNull { (it as? JsonObject)?.let(::parseSubtitle) } ?: emptyList()
