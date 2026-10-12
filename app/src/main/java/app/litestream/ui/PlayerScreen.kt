package app.litestream.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.litestream.AppViewModel
import app.litestream.R
import app.litestream.data.PlaybackRequest
import app.litestream.data.Subtitle
import app.litestream.data.Video
import app.litestream.data.WatchEntry
import app.litestream.data.nextVideo
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.input.key.nativeKeyEvent

private enum class Panel { NONE, SPEED, TRACKS, INFO }

@Composable
fun PlayerScreen(
    vm: AppViewModel,
    onExit: () -> Unit,
    onSources: (type: String, id: String, title: String) -> Unit
) {
    val req = vm.playback
    if (req == null) {
        LaunchedEffect(Unit) { onExit() }
    } else {
        PlayerContent(vm, req, onExit, onSources)
    }
}

@Composable
private fun PlayerContent(
    vm: AppViewModel,
    req: PlaybackRequest,
    onExit: () -> Unit,
    onSources: (String, String, String) -> Unit
) {
    val view = LocalView.current

    // Immersive fullscreen while playing
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    var subs by remember(req) { mutableStateOf<List<Subtitle>?>(null) }
    LaunchedEffect(req) { subs = req.embeddedSubs + vm.repo.subtitles(req.type, req.id) }

    val s = subs
    if (s == null) {
        BackHandler { onExit() }
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { CircularProgressIndicator() }
    } else {
        PlayerSurface(vm, req, s, onExit, onSources)
    }
}

@Composable
private fun PlayerSurface(
    vm: AppViewModel,
    req: PlaybackRequest,
    subs: List<Subtitle>,
    onExit: () -> Unit,
    onSources: (String, String, String) -> Unit
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val exo = remember(req) {
        buildPlayer(ctx, req, subs, (vm.repo.resumePosition(req.id) - 3000).coerceAtLeast(0))
    }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var buffered by remember { mutableStateOf(0L) }
    var speed by remember { mutableStateOf(1f) }
    var tracks by remember { mutableStateOf(exo.currentTracks) }
    var videoInfo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var resizeIdx by remember { mutableStateOf(0) }
    var subScale by remember { mutableStateOf(1f) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var gestureHud by remember { mutableStateOf<String?>(null) }
    var nextEp by remember(req) { mutableStateOf<Video?>(null) }
    var nextLoading by remember(req) { mutableStateOf(false) }
    var hideNonce by remember { mutableStateOf(0) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    val audioManager = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var brightness by remember { mutableStateOf(0.5f) }

    // ---------- helpers ----------
    fun showControls() { controls = true; hideNonce++ }

    fun seekBy(delta: Long) {
        val d = exo.duration
        var t = exo.currentPosition + delta
        if (d != C.TIME_UNSET && d > 0) t = t.coerceAtMost(d)
        t = t.coerceAtLeast(0)
        exo.seekTo(t)
        position = t
        seekFlash = (if (delta > 0) "+" else "−") + (abs(delta) / 1000) + "s"
        hideNonce++
    }

    fun seekToAbs(t: Long) { exo.seekTo(t); position = t; hideNonce++ }

    fun toggle() { if (exo.playWhenReady) exo.pause() else exo.play() }

    fun saveProgress() {
        val d = exo.duration
        val p = exo.currentPosition
        if (d == C.TIME_UNSET || d <= 0) return
        if (p > d * 0.95) {
            vm.repo.removeHistory(req.id)
        } else if (p > 5_000) {
            vm.repo.upsertHistory(
                WatchEntry(
                    videoId = req.id, metaId = req.metaId, type = req.type, title = req.title,
                    subtitle = req.subtitle, thumb = req.thumb, streamUrl = req.url, addon = req.addon,
                    quality = req.quality, positionMs = p, durationMs = d
                )
            )
        }
    }

    fun doNext() {
        val n = nextEp ?: return
        if (nextLoading) return
        nextLoading = true
        vm.playNext(req, n) {
            nextLoading = false
            onSources("series", n.id, req.title)
        }
    }

    // ---------- effects ----------
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { isPlaying = playWhenReady }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    vm.repo.removeHistory(req.id)
                    if (nextEp != null) doNext() else onExit()
                }
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) { speed = p.speed }
            override fun onPlayerError(e: PlaybackException) { error = "${e.errorCodeName}\n${e.message ?: ""}" }
        }
        exo.addListener(listener)
        onDispose {
            saveProgress()
            exo.removeListener(listener)
            exo.release()
            activity?.window?.let { w ->
                val a = w.attributes
                a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = a
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, exo) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) { saveProgress(); exo.pause() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            val d = exo.duration
            duration = if (d == C.TIME_UNSET) 0L else d
            buffered = exo.bufferedPosition
            videoInfo = describeVideo(exo)
            delay(500)
        }
    }
    LaunchedEffect(exo) {
        while (true) { delay(5_000); saveProgress() }
    }
    LaunchedEffect(exo) {
        val p = vm.repo.resumePosition(req.id)
        if (p > 5_000) gestureHud = "Resuming from ${formatTime(p)}"
    }
    LaunchedEffect(req) {
        if (req.type == "series") {
            val m = vm.currentMeta?.takeIf { req.id.startsWith(it.id) } ?: vm.repo.meta("series", req.metaId)
            nextEp = m?.let { nextVideo(it, req.id) }
        }
    }
    LaunchedEffect(controls, hideNonce, isPlaying, panel, error) {
        if (controls && isPlaying && panel == Panel.NONE && error == null) {
            delay(4_500)
            controls = false
        }
    }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(900); seekFlash = null } }
    LaunchedEffect(gestureHud) { if (gestureHud != null) { delay(1_500); gestureHud = null } }
    LaunchedEffect(controls, panel, error) {
        delay(70)
        runCatching {
            when {
                error != null -> retryFocus.requestFocus()
                panel == Panel.INFO || (panel == Panel.NONE && !controls) -> rootFocus.requestFocus()
                panel == Panel.NONE && controls -> playFocus.requestFocus()
                else -> Unit
            }
        }
    }
    LaunchedEffect(resizeIdx, playerView) {
        playerView?.resizeMode = when (resizeIdx) {
            0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }
    LaunchedEffect(subScale, playerView) {
        playerView?.subtitleView?.setFractionalTextSize(0.0533f * subScale)
    }

    BackHandler {
        if (panel != Panel.NONE) { panel = Panel.NONE; showControls() } else onExit()
    }

    // ---------- UI ----------
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || error != null) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.MediaPlayPause -> { toggle(); true }
                    Key.MediaPlay -> { exo.play(); true }
                    Key.MediaPause -> { exo.pause(); true }
                    Key.MediaFastForward -> { seekBy(30_000); true }
                    Key.MediaRewind -> { seekBy(-30_000); true }
                    else -> {
                        if (panel != Panel.NONE) {
                            false
                        } else if (controls) {
                            hideNonce++
                            false
                        } else {
                            when (ev.key) {
                                Key.DirectionLeft -> { seekBy(-10_000); true }
                                Key.DirectionRight -> { seekBy(10_000); true }
                                Key.DirectionUp, Key.DirectionDown -> { showControls(); true }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { toggle(); showControls(); true }
                                else -> false
                            }
                        }
                    }
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (panel != Panel.NONE) { panel = Panel.NONE; showControls() }
                        else if (controls) controls = false
                        else showControls()
                    },
                    onDoubleTap = { off -> if (off.x < size.width / 2f) seekBy(-10_000) else seekBy(10_000) }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    val h = size.height.toFloat()
                    if (change.position.x < size.width / 2f) {
                        brightness = (brightness - dy / h).coerceIn(0.02f, 1f)
                        activity?.window?.let { w ->
                            val a = w.attributes
                            a.screenBrightness = brightness
                            w.attributes = a
                        }
                        gestureHud = "Brightness ${(brightness * 100).roundToInt()}%"
                    } else {
                        volume = (volume - dy / h * maxVol * 1.5f).coerceIn(0f, maxVol.toFloat())
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume.roundToInt(), 0)
                        gestureHud = "Volume ${volume.roundToInt()}/$maxVol"
                    }
                }
            }
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    player = exo
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---- main controls ----
        AnimatedVisibility(
            visible = controls && error == null && panel == Panel.NONE,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent, Color.Transparent, Color(0xE6000000))
                        )
                    )
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // top bar
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PBtn(onClick = onExit) { Icon(Icons.Default.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            req.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        req.subtitle?.let {
                            Text(it, color = Color(0xFFCCCCCC), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (videoInfo.isNotEmpty()) Text(videoInfo, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                }

                // center transport
                Row(
                    Modifier.align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleBtn(64.dp, onClick = { seekBy(-10_000) }) { SeekIcon(false) }
                    CircleBtn(88.dp, onClick = { toggle() }, modifier = Modifier.focusRequester(playFocus)) {
                        Icon(
                            painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            if (isPlaying) "Pause" else "Play", Modifier.size(48.dp)
                        )
                    }
                    CircleBtn(64.dp, onClick = { seekBy(10_000) }) { SeekIcon(true) }
                }

                // bottom: seekbar + actions
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(formatTime(position), color = Color.White, fontSize = 14.sp)
                        SeekBar(
                            position, duration, buffered,
                            onStep = { seekBy(it) }, onSeekTo = { seekToAbs(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Text("-" + formatTime((duration - position).coerceAtLeast(0)), color = Color.White, fontSize = 14.sp)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PBtn(onClick = { panel = Panel.SPEED; controls = false }) { Text("Speed ${speedLabel(speed)}") }
                        PBtn(onClick = { panel = Panel.TRACKS; controls = false }) { Text("Audio & Subtitles") }
                        PBtn(onClick = { resizeIdx = (resizeIdx + 1) % 3 }) { Text(listOf("Fit", "Zoom", "Stretch")[resizeIdx]) }
                        if (req.type == "series" && position < 6 * 60_000L) {
                            PBtn(onClick = { seekBy(85_000) }) { Text("Skip Intro") }
                        }
                        PBtn(onClick = { onSources(req.type, req.id, req.title) }) { Text("Sources") }
                        PBtn(onClick = { panel = Panel.INFO; controls = false }) { Text("Info") }
                        if (nextEp != null) {
                            PBtn(onClick = { doNext() }) {
                                Icon(painterResource(R.drawable.ic_next), null, Modifier.size(20.dp))
                                Text("Next Episode")
                            }
                        }
                    }
                }
            }
        }

        // ---- thin progress bar while seeking with controls hidden ----
        if (!controls && seekFlash != null && duration > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(
                    Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        // ---- HUD text ----
        val hud = if (!controls) seekFlash ?: gestureHud else gestureHud
        hud?.let {
            Text(
                it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xAA000000)).padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        if ((buffering || nextLoading) && error == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                if (nextLoading) Text("Loading next episode…", color = Color.White)
            }
        }

        // ---- side panels ----
        if (panel != Panel.NONE) {
            Box(Modifier.align(Alignment.CenterEnd)) {
                when (panel) {
                    Panel.SPEED -> SidePanel("Playback speed") {
                        val fr = remember { FocusRequester() }
                        LaunchedEffect(Unit) { delay(90); runCatching { fr.requestFocus() } }
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                            val sel = abs(speed - s) < 0.01f
                            OptionRow(speedLabel(s), sel, focusRequester = if (sel) fr else null) {
                                exo.setPlaybackSpeed(s)
                            }
                        }
                    }
                    Panel.TRACKS -> SidePanel("Audio & Subtitles") {
                        val audio = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
                        val text = trackOptions(tracks, C.TRACK_TYPE_TEXT)
                        val textOff = text.none { it.selected }
                        val fr = remember { FocusRequester() }
                        LaunchedEffect(Unit) { delay(90); runCatching { fr.requestFocus() } }

                        PanelLabel("Audio")
                        if (audio.isEmpty()) Text("No audio tracks", color = Color(0xFF999999))
                        audio.forEachIndexed { i, o ->
                            val focusHere = o.selected || (i == 0 && audio.none { it.selected })
                            OptionRow(o.label, o.selected, o.supported, if (focusHere) fr else null) {
                                selectTrack(exo, C.TRACK_TYPE_AUDIO, o)
                            }
                        }
                        PanelLabel("Subtitles")
                        OptionRow("Off", textOff, true, if (audio.isEmpty()) fr else null) {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        }
                        text.forEach { o ->
                            OptionRow(o.label, o.selected, o.supported) { selectTrack(exo, C.TRACK_TYPE_TEXT, o) }
                        }
                        PanelLabel("Subtitle size")
                        listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.4f, "Huge" to 1.8f).forEach { (n, v) ->
                            OptionRow(n, subScale == v) { subScale = v }
                        }
                    }
                    Panel.INFO -> SidePanel("Playback info") {
                        InfoLine("Video", videoInfo.ifEmpty { "—" })
                        InfoLine("Audio", describeAudio(exo))
                        InfoLine("Provider", req.addon.ifEmpty { "—" })
                        InfoLine("Speed", speedLabel(speed))
                        if (req.streamInfo.isNotBlank()) {
                            PanelLabel("Source")
                            Text(req.streamInfo, color = Color(0xFFBBBBBB), fontSize = 13.sp)
                        }
                    }
                    Panel.NONE -> {}
                }
            }
        }

        // ---- error ----
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6101014)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("This source couldn't be played", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(msg, color = Color(0xFFBBBBBB), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PBtn(onClick = { error = null; exo.prepare(); exo.play() }, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
                    PBtn(onClick = { onSources(req.type, req.id, req.title) }) { Text("Choose another source") }
                    PBtn(onClick = onExit) { Text("Back") }
                }
            }
        }
    }
}

// ---------- small components ----------

@Composable
private fun PBtn(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.onFocusChanged { focused = it.hasFocus }
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) Color.White else Color(0x44FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides if (focused) Color.Black else Color.White) { content() }
    }
}

@Composable
private fun CircleBtn(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(size).onFocusChanged { focused = it.hasFocus }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color(0x44FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides if (focused) Color.Black else Color.White) { content() }
    }
}

@Composable
private fun SeekIcon(forward: Boolean) {
    Box(Modifier.size(34.dp), Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_replay), null,
            Modifier.fillMaxSize().then(if (forward) Modifier.scale(-1f, 1f) else Modifier)
        )
        Text("10", fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    onStep: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufFrac = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary

    Canvas(
        modifier.height(30.dp)
            .onFocusChanged { focused = it.hasFocus }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val rc = ev.nativeKeyEvent.repeatCount
                val step = if (rc > 8) 60_000L else if (rc > 3) 30_000L else 10_000L
                when (ev.key) {
                    Key.DirectionLeft -> { onStep(-step); true }
                    Key.DirectionRight -> { onStep(step); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(duration) {
                detectTapGestures { off ->
                    if (duration > 0) onSeekTo((off.x / size.width * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { if (duration > 0) onSeekTo((it * duration).toLong()) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                )
            }
    ) {
        val cy = size.height / 2f
        val th = (if (focused) 8.dp else 4.dp).toPx()
        val r = CornerRadius(th / 2f)
        drawRoundRect(Color(0x55FFFFFF), Offset(0f, cy - th / 2f), Size(size.width, th), r)
        drawRoundRect(Color(0x88FFFFFF), Offset(0f, cy - th / 2f), Size(size.width * bufFrac, th), r)
        drawRoundRect(primary, Offset(0f, cy - th / 2f), Size(size.width * frac, th), r)
        drawCircle(
            if (focused) Color.White else primary,
            radius = (if (focused) 11.dp else 7.dp).toPx(),
            center = Offset(size.width * frac, cy)
        )
    }
}

@Composable
private fun SidePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(400.dp).background(Color(0xEE101014))
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun PanelLabel(t: String) {
    Text(t, color = Color(0xFF9E9EA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun InfoLine(k: String, v: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(k, color = Color(0xFF9E9EA8), fontSize = 12.sp)
        Text(v, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun OptionRow(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.hasFocus }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val c = if (focused) Color.Black else if (enabled) Color.White else Color(0xFF777777)
        Text(if (selected) "✓" else "", color = c, modifier = Modifier.width(24.dp))
        Text(text + if (!enabled) " (unsupported)" else "", color = c, fontSize = 16.sp)
    }
}

// ---------- track helpers ----------

private data class TrackOpt(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean, val supported: Boolean)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOpt> {
    val raw = tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).map { i -> Triple(g, i, g.getTrackFormat(i)) }
    }
    return raw.mapIndexed { n, (g, i, f) ->
        val lang = f.language?.takeIf { it != "und" }?.let {
            Locale.forLanguageTag(it).displayLanguage.ifBlank { it.uppercase() }
        } ?: "Track ${n + 1}"
        val extra = mutableListOf<String>()
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> extra += "Mono"
                2 -> extra += "Stereo"
                6 -> extra += "5.1"
                8 -> extra += "7.1"
                else -> if (f.channelCount > 0) extra += "${f.channelCount}ch"
            }
            audioCodecName(f.sampleMimeType)?.let { extra += it }
        } else {
            subtitleFormatName(f.sampleMimeType)?.let { extra += it }
        }
        f.label?.takeIf { it.isNotBlank() && !it.equals(lang, true) }?.let { extra += it }
        val label = lang + if (extra.isNotEmpty()) " · " + extra.joinToString(" · ") else ""
        TrackOpt(g, i, label, g.isTrackSelected(i), g.isTrackSupported(i))
    }
}

private fun selectTrack(exo: ExoPlayer, type: Int, o: TrackOpt) {
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, false)
        .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        .build()
}

private fun audioCodecName(mime: String?): String? = when (mime) {
    "audio/ac3" -> "Dolby Digital"
    "audio/eac3" -> "Dolby Digital+"
    "audio/eac3-joc" -> "Dolby Atmos"
    "audio/true-hd" -> "TrueHD"
    "audio/vnd.dts" -> "DTS"
    "audio/vnd.dts.hd" -> "DTS-HD"
    "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/flac" -> "FLAC"
    "audio/mpeg" -> "MP3"
    else -> mime?.substringAfter('/')
}

private fun subtitleFormatName(mime: String?): String? = when (mime) {
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_VTT -> "VTT"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    else -> mime?.substringAfter('/')
}

private fun speedLabel(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

private fun describeVideo(exo: ExoPlayer): String {
    val f = exo.videoFormat ?: return ""
    val res = if (f.width > 0) "${f.width}×${f.height}" else ""
    val mime = f.sampleMimeType ?: ""
    val codec = when {
        "hevc" in mime -> "HEVC"
        "avc" in mime -> "H.264"
        "av01" in mime -> "AV1"
        "dolby-vision" in mime -> "Dolby Vision"
        "vp9" in mime -> "VP9"
        else -> ""
    }
    val hdr = when (f.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> ""
    }
    val fps = if (f.frameRate > 0) String.format(Locale.US, "%.2f", f.frameRate).trimEnd('0').trimEnd('.') + " fps" else ""
    return listOf(res, codec, hdr, fps).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun describeAudio(exo: ExoPlayer): String {
    val f = exo.audioFormat ?: return "—"
    val ch = when (f.channelCount) { 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (f.channelCount > 0) "${f.channelCount}ch" else "" }
    return listOfNotNull(audioCodecName(f.sampleMimeType), ch.ifEmpty { null }).joinToString(" · ").ifEmpty { "—" }
}

// ---------- player construction ----------

private fun buildPlayer(ctx: Context, req: PlaybackRequest, subs: List<Subtitle>, startMs: Long): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("LiteStream/1.0")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    val renderers = DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    val selector = DefaultTrackSelector(ctx).apply {
        setParameters(buildUponParameters().setPreferredAudioLanguage("eng"))
    }

    val player = ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
        .setTrackSelector(selector)
        .setSeekForwardIncrementMs(10_000)
        .setSeekBackIncrementMs(10_000)
        .setHandleAudioBecomingNoisy(true)
        .build()
    player.setAudioAttributes(
        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        true
    )

    val subConfigs = subs.filter { it.url.startsWith("http") }.take(25).map { s ->
        val vtt = s.url.substringBefore('?').endsWith(".vtt")
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
            .setMimeType(if (vtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(s.lang)
            .setLabel(s.lang.uppercase())
            .build()
    }
    val item = MediaItem.Builder().setUri(req.url).setSubtitleConfigurations(subConfigs).build()
    player.setMediaItem(item, startMs)
    player.prepare()
    player.playWhenReady = true
    return player
}
