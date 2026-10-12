package app.litestream.data

import java.util.Locale

data class ParsedStream(
    val addon: String,
    val stream: Stream,
    val url: String,
    val quality: Int,
    val badges: List<String>,
    val sizeBytes: Long,
    val seeders: Int,
    val text: String,
    val name: String,
    val title: String,
    val description: String,
    val filename: String?,
    val cached: Boolean?
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

fun formatSize(b: Long): String = when {
    b <= 0 -> ""
    b >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", b / 1e9)
    else -> String.format(Locale.US, "%.0f MB", b / 1e6)
}

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
    if (Regex("10[- ]?bit").containsMatchIn(low)) badges += "10-bit"
    if (Regex("hevc|x265|h\\.?265").containsMatchIn(low)) badges += "HEVC"
    if (Regex("x264|h\\.?264|avc").containsMatchIn(low)) badges += "H.264"
    if ("av1" in low) badges += "AV1"
    if ("remux" in low) badges += "REMUX"
    when {
        Regex("blu-?ray|bdrip|brrip").containsMatchIn(low) -> badges += "BluRay"
        Regex("web-?dl").containsMatchIn(low) -> badges += "WEB-DL"
        "webrip" in low -> badges += "WEBRip"
        "hdtv" in low -> badges += "HDTV"
        Regex("\\bcam\\b|hdcam|telesync|\\bts\\b").containsMatchIn(low) -> badges += "CAM"
    }
    when {
        "atmos" in low -> badges += "Atmos"
        "truehd" in low -> badges += "TrueHD"
        Regex("dts-?hd|dts:x").containsMatchIn(low) -> badges += "DTS-HD"
        Regex("\\bdts\\b").containsMatchIn(low) -> badges += "DTS"
        Regex("ddp|dd\\+|e-?ac-?3").containsMatchIn(low) -> badges += "DD+"
        Regex("\\bdd\\b|\\bac-?3\\b").containsMatchIn(low) -> badges += "DD"
        "aac" in low -> badges += "AAC"
    }
    if ("7.1" in low) badges += "7.1" else if ("5.1" in low) badges += "5.1"

    val cached: Boolean? = when {
        "[tb+]" in low || "⚡" in low -> true
        "[tb download]" in low || "[tb ⬇" in low -> false
        else -> null
    }
    if (cached == true) badges += "Cached" else if (cached == false) badges += "Uncached"

    val size = s.behaviorHints?.videoSize ?: sizeRegex.find(text)?.let {
        val n = it.groupValues[1].toDouble()
        when (it.groupValues[2].uppercase()) {
            "TB" -> n * 1e12
            "GB" -> n * 1e9
            else -> n * 1e6
        }.toLong()
    } ?: 0L
    val seeders = seedRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    ParsedStream(
        addon = item.addonName, stream = s, url = url, quality = quality, badges = badges,
        sizeBytes = size, seeders = seeders, text = text,
        name = (s.name ?: "").replace("\n", " · ").trim(),
        title = (s.title ?: "").trim(),
        description = (s.description ?: "").trim(),
        filename = s.behaviorHints?.filename,
        cached = cached
    )
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
