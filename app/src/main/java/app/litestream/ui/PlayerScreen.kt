package app.litestream.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import app.litestream.AppViewModel
import app.litestream.data.PlaybackRequest
import app.litestream.data.Subtitle

@Composable
fun PlayerScreen(vm: AppViewModel, onExit: () -> Unit) {
    val req = vm.playback
    if (req == null) {
        LaunchedEffect(Unit) { onExit() }
    } else {
        PlayerContent(vm, req, onExit)
    }
}

@Composable
private fun PlayerContent(vm: AppViewModel, req: PlaybackRequest, onExit: () -> Unit) {
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

    BackHandler { onExit() }

    val s = subs
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { CircularProgressIndicator() }
    } else {
        PlayerSurface(vm, req, s)
    }
}

@Composable
private fun PlayerSurface(vm: AppViewModel, req: PlaybackRequest, subs: List<Subtitle>) {
    val ctx = LocalContext.current
    val exo = remember(req) { buildPlayer(ctx, req, subs, vm.repo.position(req.mediaKey)) }
    var error by remember(req) { mutableStateOf<String?>(null) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName}\n${e.message ?: ""}"
            }
        }
        exo.addListener(listener)
        onDispose {
            val d = exo.duration
            val p = exo.currentPosition
            vm.repo.savePosition(req.mediaKey, if (d > 0 && p > d * 0.95) 0L else p)
            exo.removeListener(listener)
            exo.release()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, exo) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) exo.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // PlayerView's built-in controller: seekbar, play/pause, ±10s, and the settings (gear)
        // menu with playback speed + audio track; CC button for subtitles. D-pad friendly.
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    keepScreenOn = true
                    controllerShowTimeoutMs = 4000
                    setShowSubtitleButton(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    post { requestFocus() }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        error?.let { Text("Playback error\n$it", color = Color.White, modifier = Modifier.align(Alignment.Center)) }
    }
}

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
