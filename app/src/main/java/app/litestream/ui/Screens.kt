@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.litestream.AppViewModel
import app.litestream.data.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

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
fun HomeScreen(vm: AppViewModel, onOpen: (Meta) -> Unit) {
    val addons by vm.addons.collectAsState()
    val rows by vm.home.collectAsState()
    val loading by vm.homeLoading.collectAsState()
    LaunchedEffect(addons) { vm.loadHome() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { Text("Home", style = MaterialTheme.typography.headlineMedium) }
        if (addons.isEmpty()) {
            item { Text("No addons yet. Loading the default catalog… or add one in Settings.") }
        } else if (rows.isEmpty() && loading) {
            item { CircularProgressIndicator() }
        }
        items(rows, key = { it.order }) { row -> CatalogRowView(row, onOpen) }
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen(vm: AppViewModel, onOpen: (Meta) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val rows by vm.searchResults.collectAsState()
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
        items(rows, key = { it.order }) { row -> CatalogRowView(row, onOpen) }
    }
}

// ---------- Detail ----------

@Composable
fun DetailScreen(vm: AppViewModel, type: String, id: String, onStreams: (String, String, String) -> Unit) {
    var meta by remember(type, id) { mutableStateOf<Meta?>(null) }
    var failed by remember(type, id) { mutableStateOf(false) }
    LaunchedEffect(type, id) {
        meta = vm.repo.meta(type, id)
        failed = meta == null
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
        if (!loading && parsed.isEmpty()) {
            item { Text("No playable sources found. Install a stream addon (e.g. Torrentio/TorBox) in Settings.") }
        }
        items(parsed) { p ->
            StreamRow(p) {
                vm.playback = PlaybackRequest(
                    url = p.url, title = title.ifBlank { id }, mediaKey = id, type = type, id = id,
                    embeddedSubs = p.stream.subtitles
                )
                onPlay()
            }
        }
    }
}

@Composable
private fun StreamRow(p: ParsedStream, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().focusBorder().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.addon, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            p.badges.forEach { Badge(it) }
        }
        Text(p.text, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
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
