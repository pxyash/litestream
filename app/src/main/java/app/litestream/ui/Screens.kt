@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.litestream.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.litestream.AppViewModel
import app.litestream.data.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ---------- shared pieces ----------

/** Visible D-pad focus ring. Must come BEFORE .clickable in the modifier chain. */
@Composable
fun Modifier.focusBorder(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .border(
            if (focused) 3.dp else 0.dp,
            if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape
        )
}

@Composable
fun PosterCard(m: Meta, onClick: () -> Unit) {
    Column(
        Modifier.width(140.dp).focusBorder().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp)
    ) {
        AsyncImage(
            model = m.poster,
            contentDescription = m.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Text(
            m.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun CatalogRowView(row: CatalogRow, onOpen: (Meta) -> Unit) {
    Column {
        Text(row.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(row.items) { m -> PosterCard(m) { onOpen(m) } }
        }
    }
}

@Composable
fun SortChips(vm: AppViewModel) {
    val opts = listOf("quality" to "Quality", "size" to "Size", "seeders" to "Seeders", "addon" to "Addon order")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(opts) { (key, label) ->
            FilterChip(selected = vm.sortMode == key, onClick = { vm.setSort(key) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Badge(t: String) {
    Text(
        t, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

// ---------- Home ----------

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpen: (Meta) -> Unit,
    onResume: (WatchEntry) -> Unit,
    onPickSource: (WatchEntry) -> Unit,
    onDetails: (WatchEntry) -> Unit
) {
    val addons by vm.addons.collectAsState()
    val rows by vm.home.collectAsState()
    val loading by vm.homeLoading.collectAsState()
    val history by vm.repo.history.collectAsState()
    val continueList = remember(history) { history.distinctBy { it.metaId } }
    var selected by remember { mutableStateOf<WatchEntry?>(null) }
    LaunchedEffect(addons) { vm.loadHome() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { Text("Home", style = MaterialTheme.typography.headlineMedium) }
        if (continueList.isNotEmpty()) {
            item {
                Column {
                    Text(
                        "Continue Watching", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueList) { e -> ContinueCard(e) { selected = e } }
                    }
                }
            }
        }
        if (addons.isEmpty()) {
            item { Text("No addons yet. Loading the default catalog… or add one in Settings.") }
        } else if (rows.isEmpty() && loading) {
            item { CircularProgressIndicator() }
        }
        items(rows, key = { it.order }) { row -> CatalogRowView(row, onOpen) }
    }

    selected?.let { e ->
        ContinueDialog(
            e,
            onDismiss = { selected = null },
            onResume = { selected = null; onResume(e) },
            onPick = { selected = null; onPickSource(e) },
            onDetails = { selected = null; onDetails(e) },
            onRemove = { selected = null; vm.repo.removeHistory(e.videoId) }
        )
    }
}

@Composable
private fun ContinueCard(e: WatchEntry, onClick: () -> Unit) {
    Column(
        Modifier.width(240.dp).focusBorder().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = e.thumb, contentDescription = e.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Icon(
                Icons.Default.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(40.dp)
            )
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                    .background(Color(0x66FFFFFF))
            ) {
                Box(
                    Modifier.fillMaxWidth(e.progress).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text(
            e.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)
        )
        val left = ((e.durationMs - e.positionMs) / 60000).toInt().coerceAtLeast(0)
        Text(
            listOfNotNull(e.subtitle, "$left min left").joinToString(" · "),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ContinueDialog(
    e: WatchEntry,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    onPick: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit
) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { fr.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(e.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                e.subtitle?.let { Text(it) }
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth().focusRequester(fr)) {
                    Text("Resume from ${formatTime(e.positionMs)}")
                }
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose another source") }
                OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) { Text("Details") }
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Remove from Continue Watching") }
            }
        }
    )
}

// ---------- Search ----------

@Composable
fun SearchScreen(vm: AppViewModel, onOpen: (Meta) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val rows by vm.searchResults.collectAsState()
    val status by vm.searchStatus.collectAsState()
    LaunchedEffect(q) { delay(500); vm.search(q) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            OutlinedTextField(
                value = q, onValueChange = { q = it },
                placeholder = { Text("Search movies & series") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(q) }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        status?.let { s -> item { Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(rows, key = { it.order }) { row -> CatalogRowView(row, onOpen) }
    }
}

// ---------- Detail ----------

@Composable
fun DetailScreen(vm: AppViewModel, type: String, id: String, onStreams: (String, String, String) -> Unit) {
    var meta by remember(type, id) { mutableStateOf<Meta?>(null) }
    var failed by remember(type, id) { mutableStateOf(false) }
    LaunchedEffect(type, id) {
        val m = vm.repo.meta(type, id)
        meta = m
        vm.currentMeta = m
        failed = m == null
    }
    val m = meta
    if (m == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (failed) Text("Couldn't load details. Check your addons.") else CircularProgressIndicator()
        }
    } else {
        DetailContent(m, onStreams)
    }
}

@Composable
private fun DetailContent(m: Meta, onStreams: (String, String, String) -> Unit) {
    val episodes = remember(m) { m.videos.filter { it.season != null && it.ep != null } }
    val seasons = remember(m) { episodes.mapNotNull { it.season }.distinct().sorted() }
    var season by remember(m) { mutableStateOf(seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1) }
    val isSeries = episodes.isNotEmpty()
    val first = remember(m) {
        episodes.filter { (it.season ?: 0) > 0 }
            .minWithOrNull(compareBy<Video>({ it.season }, { it.ep })) ?: episodes.firstOrNull()
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(m.id) { runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = m.background ?: m.poster, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.18f)
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    AsyncImage(
                        model = m.poster, contentDescription = m.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(180.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(m.name, style = MaterialTheme.typography.headlineLarge)
                        val info = listOfNotNull(
                            m.releaseInfo ?: m.year, m.runtime, m.imdbRating?.let { "★ $it" }
                        ).joinToString("  •  ")
                        if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.titleSmall)
                        if (m.genres.isNotEmpty()) {
                            Text(m.genres.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val t = first
                                if (isSeries && t != null) onStreams("series", t.id, "${m.name} S${t.season}E${t.ep}")
                                else onStreams(m.type, m.id, m.name)
                            },
                            modifier = Modifier.focusRequester(playFocus)
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isSeries && first != null) "Play S${first.season}E${first.ep}" else "Play")
                        }
                        m.description?.let { Text(it, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                        if (m.cast.isNotEmpty()) {
                            Text("Cast: ${m.cast.take(8).joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (m.director.isNotEmpty()) {
                            Text("Director: ${m.director.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (isSeries) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            FilterChip(
                                selected = s == season, onClick = { season = s },
                                label = { Text(if (s == 0) "Specials" else "Season $s") }
                            )
                        }
                    }
                }
                items(episodes.filter { it.season == season }.sortedBy { it.ep }) { v ->
                    EpisodeRow(v) { onStreams("series", v.id, "${m.name} S${v.season}E${v.ep}") }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(v: Video, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusBorder().clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (v.thumbnail != null) {
            AsyncImage(
                model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Column(Modifier.weight(1f)) {
            Text("${v.ep}. ${v.label}", style = MaterialTheme.typography.titleSmall)
            v.released?.take(10)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            v.overview?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------- Streams (sources) ----------

@Composable
fun StreamsScreen(vm: AppViewModel, type: String, id: String, title: String, onPlay: () -> Unit) {
    var all by remember { mutableStateOf<List<AddonStream>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var provider by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(type, id) {
        vm.repo.streams(type, id).collect { all = it }
        loading = false
    }
    val providers = remember(all) { all.map { it.addonName }.distinct() }
    val parsed = remember(all, vm.sortMode, provider) {
        sortStreams(parseStreams(all).filter { provider == null || it.addon == provider }, vm.sortMode)
    }

    fun play(p: ParsedStream) {
        val meta = vm.currentMeta?.takeIf { id == it.id || id.startsWith(it.id + ":") }
        val prev = vm.repo.history.value.firstOrNull { it.videoId == id }
        val video = meta?.videos?.firstOrNull { it.id == id }
        vm.playback = PlaybackRequest(
            url = p.url,
            title = meta?.name ?: prev?.title ?: title.ifBlank { id },
            subtitle = video?.tag() ?: prev?.subtitle,
            type = type,
            id = id,
            metaId = meta?.id ?: prev?.metaId ?: id.substringBefore(":"),
            thumb = video?.thumbnail ?: meta?.background ?: meta?.poster ?: prev?.thumb,
            addon = p.addon,
            quality = p.quality,
            streamInfo = p.text,
            embeddedSubs = p.stream.subtitles
        )
        onPlay()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title.ifBlank { "Sources" }, style = MaterialTheme.typography.headlineSmall)
                if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        item { Text("Provider", style = MaterialTheme.typography.labelMedium) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = provider == null, onClick = { provider = null }, label = { Text("All") }) }
                items(providers) { p ->
                    FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p) })
                }
            }
        }
        item { Text("Sort by", style = MaterialTheme.typography.labelMedium) }
        item { SortChips(vm) }
        item { Text("${parsed.size} sources", style = MaterialTheme.typography.labelMedium) }
        if (!loading && parsed.isEmpty()) {
            item { Text("No playable sources found. Install a stream addon (e.g. Torrentio/TorBox) in Settings.") }
        }
        items(parsed) { p -> StreamRow(p) { play(p) } }
    }
}

@Composable
private fun StreamRow(p: ParsedStream, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().focusBorder().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.addon, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (p.name.isNotBlank()) {
                Text(p.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (p.badges.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.badges.forEach { Badge(it) }
            }
        }
        if (p.title.isNotBlank()) {
            Text(p.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (p.description.isNotBlank()) {
            Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.title.isBlank() && p.description.isBlank() && p.name.isBlank()) {
            Text(p.text, style = MaterialTheme.typography.bodySmall)
        }
        p.filename?.takeIf { it.isNotBlank() && it !in p.title }?.let {
            Text("File: $it", style = MaterialTheme.typography.bodySmall)
        }
        val facts = listOfNotNull(
            formatSize(p.sizeBytes).takeIf { it.isNotEmpty() }?.let { "Size: $it" },
            p.seeders.takeIf { it > 0 }?.let { "Seeders: $it" },
            Uri.parse(p.url).host?.let { "Host: $it" }
        )
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("   •   "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val addons by vm.addons.collectAsState()
    val status by vm.installStatus.collectAsState()
    var url by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Install addon", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Addon URL (…/manifest.json or stremio://…)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.install(url) { url = "" } }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.install(url) { url = "" } }) { Text("Install") }
                status?.let { Text(it) }
            }
        }
        item { Text("Default stream sort", style = MaterialTheme.typography.titleMedium) }
        item { SortChips(vm) }
        item { Text("Watch history", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton(onClick = { vm.repo.clearHistory() }) { Text("Clear Continue Watching") } }
        item { Text("Installed addons", style = MaterialTheme.typography.titleMedium) }
        items(addons) { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${a.manifest.name} ${a.manifest.version}", style = MaterialTheme.typography.titleSmall)
                    a.manifest.description?.let {
                        Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    // Only show the host: the full URL may contain your TorBox API key.
                    Text(Uri.parse(a.baseUrl).host ?: "", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { vm.repo.remove(a.baseUrl) }) { Icon(Icons.Default.Delete, "Remove") }
            }
        }
    }
}
