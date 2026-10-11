package app.litestream.data

data class ParsedStream(
    val addon: String,
    val stream: Stream,
    val url: String,
    val quality: Int,
    val badges: List<String>,
    val sizeBytes: Long,
    val seeders: Int,
    val text: String
) {
    val hdrScore: Int
        get() = when {
            "DV" in badges -> 3
            "HDR10+" in badges -> 2
            "HDR10" in badges || "HDR" in badges -> 1
            else -> 0
        }
}

private val sizeRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(TB|GB|MB)", RegexOption.IGNORE_CASE)
private val seedRegex = Regex("👤\\s*(\\d+)")

fun parseStreams(list: List<AddonStream>): List<ParsedStream> = list.mapNotNull { item ->
    val s = item.stream
    val url = s.url ?: return@mapNotNull null
    if (!url.startsWith("http")) return@mapNotNull null

    val text = listOfNotNull(s.name, s.title, s.description, s.behaviorHints?.filename)
        .joinToString("\n").trim()
    val low = text.lowercase()

    val quality = when {
        Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(low) -> 2160
        "1440p" in low -> 1440
        "1080p" in low -> 1080
        "720p" in low -> 720
        "480p" in low -> 480
        else -> 0
    }
    val badges = mutableListOf<String>()
    when (quality) {
        2160 -> badges += "4K"
        0 -> {}
        else -> badges += "${quality}p"
    }
    if ("dolby vision" in low || Regex("\\bdv\\b|dovi").containsMatchIn(low)) badges += "DV"
    when {
        "hdr10+" in low -> badges += "HDR10+"
        "hdr10" in low -> badges += "HDR10"
        Regex("\\bhdr\\b").containsMatchIn(low) -> badges += "HDR"
    }
    if (Regex("hevc|x265|h\\.?265").containsMatchIn(low)) badges += "HEVC"
    if ("av1" in low) badges += "AV1"
    if ("atmos" in low) badges += "Atmos"
    if ("remux" in low) badges += "REMUX"

    val size = s.behaviorHints?.videoSize ?: sizeRegex.find(text)?.let {
        val n = it.groupValues[1].toDouble()
        when (it.groupValues[2].uppercase()) {
            "TB" -> n * 1e12
            "GB" -> n * 1e9
            else -> n * 1e6
        }.toLong()
    } ?: 0L
    val seeders = seedRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    ParsedStream(item.addonName, s, url, quality, badges, size, seeders, text)
}

fun sortStreams(list: List<ParsedStream>, mode: String): List<ParsedStream> = when (mode) {
    "quality" -> list.sortedWith(
        compareByDescending<ParsedStream> { it.quality }
            .thenByDescending { it.hdrScore }
            .thenByDescending { it.sizeBytes }
    )
    "size" -> list.sortedByDescending { it.sizeBytes }
    "seeders" -> list.sortedByDescending { it.seeders }
    else -> list
}
