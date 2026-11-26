package app.mbox.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import app.mbox.Api
import app.mbox.Caption
import app.mbox.Downloads
import app.mbox.Dub
import app.mbox.HistoryEntry
import app.mbox.Pip
import app.mbox.PlaySpec
import app.mbox.R
import app.mbox.Season
import app.mbox.Staff
import app.mbox.Store
import app.mbox.StreamRes
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SCALES = listOf("Fit", "Fill", "Stretch", "Crop")

private fun Context.activityOrNull(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return c as? Activity
}

private fun isH264Codec(codec: String): Boolean {
    val c = codec.lowercase()
    return c == "h264" || c == "avc" || c == "avc1"
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

@OptIn(UnstableApi::class)
@Composable
fun WatchScreen(
    spec: PlaySpec,
    inPip: Boolean = false,
    /** Small floating window over the tab host; the same player instance keeps running. */
    mini: Boolean = false,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onOpen: (Subject) -> Unit,
) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.activityOrNull() }
    val scope = rememberCoroutineScope()
    // Decoder fallback matters on phones whose first-choice hardware decoder rejects a stream
    // (common with HLS/HEVC variants): Media3 then retries another decoder instead of ending
    // playback with a decoder error. Without it some devices close the player as soon as it starts.
    val player = remember {
        ExoPlayer.Builder(
            ctx,
            DefaultRenderersFactory(ctx).setEnableDecoderFallback(true),
        ).build()
    }

    // ------------------------------------------------------------------ catalog
    var detail by remember { mutableStateOf(Subject(title = spec.title, type = if (spec.series) 2 else 1)) }
    var dubs by remember { mutableStateOf<List<Dub>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var activeDub by remember { mutableStateOf(spec.dubId.ifBlank { spec.baseId }) }
    var se by remember { mutableStateOf(spec.se) }
    var ep by remember { mutableStateOf(if (spec.ep <= 0) 1 else spec.ep) }
    var recommendations by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var captions by remember { mutableStateOf<List<Caption>>(emptyList()) }
    var caption by remember { mutableStateOf<Caption?>(null) }
    var captionsLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showEpisodes by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<Staff?>(null) }

    // ------------------------------------------------------------------ playback
    var streams by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var current by remember { mutableStateOf<StreamRes?>(null) }
    var status by remember { mutableStateOf<String?>("Preparing stream…") }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var decoderRetries by remember { mutableStateOf(0) }
    var pendingSeek by remember { mutableStateOf(spec.startMs) }

    // ------------------------------------------------------------------ chrome
    var fullscreen by remember { mutableStateOf(false) }
    var orientationReady by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(0) } // 1 quality 2 audio 3 speed 4 settings 5 subtitle
    var speed by remember { mutableStateOf(Store.speed) }
    var scale by remember { mutableStateOf(Store.scaleMode) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var subSize by remember { mutableStateOf(Store.subSize) }
    var subBackground by remember { mutableStateOf(Store.subBackground) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // gestures
    var dragMode by remember { mutableStateOf(0) } // 1 seek, 2 brightness, 3 volume
    var previewMs by remember { mutableStateOf(0L) }
    var startPos by remember { mutableStateOf(0L) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var boosting by remember { mutableStateOf(false) }

    // next-episode countdown
    var nextIn by remember { mutableStateOf(0) }

    // resume prompt
    var resumeAsk by remember { mutableStateOf<HistoryEntry?>(null) }
    var resumeChecked by remember { mutableStateOf(false) }

    // download sheet
    var sheet by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var sheetLoading by remember { mutableStateOf(false) }
    var sheetEp by remember { mutableStateOf(1) }
    var sheetTitle by remember { mutableStateOf("") }

    val localPath = spec.localPath
    val localPlayback = localPath.isNotBlank()
    val maxEp = seasons.firstOrNull { it.se == se }?.maxEp ?: spec.maxEp

    fun setSource(item: StreamRes, startMs: Long) {
        // Everything below touches Media3 on the main thread. A malformed URL, an illegal header
        // value from the CDN referer or a released player would otherwise throw out of the
        // composition and take the whole process down, which is what "the app kicks me out when I
        // press play" looks like from the outside.
        try {
            val ds = DefaultHttpDataSource.Factory()
                .setUserAgent(Api.WEB_UA)
                .setDefaultRequestProperties(mapOf("Referer" to item.referer))
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
            val builder = MediaItem.Builder().setUri(item.url)
            caption?.let { c ->
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(c.url))
                            .setMimeType(c.mime)
                            .setLanguage(c.lang.ifBlank { "und" })
                            .setLabel(c.name)
                            .build()
                    )
                )
            }
            val mediaItem = builder.build()
            val source = when {
                item.url.contains(".m3u8", true) -> HlsMediaSource.Factory(ds).createMediaSource(mediaItem)
                item.url.contains(".mpd", true) -> DashMediaSource.Factory(ds).createMediaSource(mediaItem)
                else -> ProgressiveMediaSource.Factory(ds).createMediaSource(mediaItem)
            }
            player.setMediaSource(source)
            player.prepare()
            if (startMs > 0) player.seekTo(startMs)
            player.setPlaybackSpeed(speed)
            player.playWhenReady = true
            current = item
            error = null
            status = null
        } catch (t: Throwable) {
            status = null
            error = "This stream couldn't start (${t.javaClass.simpleName}). Try another quality or episode."
        }
    }

    fun setLocal(path: String) {
        try {
            val base = DefaultHttpDataSource.Factory().setUserAgent(Api.PLAYER_UA)
            player.setMediaSource(
                ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx, base))
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(File(path))))
            )
            player.prepare()
            player.setPlaybackSpeed(speed)
            player.playWhenReady = true
            status = null
        } catch (t: Throwable) {
            status = null
            error = "The downloaded file couldn't be opened (${t.javaClass.simpleName})."
        }
    }

    fun orderCandidates(list: List<StreamRes>): List<StreamRes> {
        if (list.isEmpty()) return list
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val target = when {
            wifi -> 1080
            Store.dataSaver -> 480
            else -> 720
        }
        val pool = list.filter { it.resolution <= target }.ifEmpty { list }
        val best = pool.maxOf { it.resolution }
        val same = pool.filter { it.resolution == best }
        val first = same.firstOrNull { isH264Codec(it.codec) } ?: same.first()
        val rest = list.filter { it !== first }.sortedWith(
            compareByDescending<StreamRes> { it.resolution }
                .thenBy { if (isH264Codec(it.codec)) 0 else 1 }
        )
        return listOf(first) + rest
    }

    LaunchedEffect(spec.baseId, localPlayback) {
        if (localPlayback || spec.baseId.isBlank()) return@LaunchedEffect
        runCatching { Api.detail(spec.baseId) }
            .onSuccess { d -> if (d.title.isNotBlank()) detail = d }
        runCatching { Api.dubs(spec.baseId) }.onSuccess { if (it.isNotEmpty()) dubs = it }
        if (spec.series) {
            runCatching { Api.seasons(spec.baseId) }.onSuccess { list ->
                seasons = list
                if (list.none { it.se == se }) se = list.firstOrNull()?.se ?: 1
            }
        }
        runCatching { Api.recommendations(spec.baseId) }
            .onSuccess { recommendations = it }
    }

    LaunchedEffect(activeDub, retryKey, localPlayback, localPath, se, ep) {
        if (localPlayback) {
            setLocal(localPath)
            return@LaunchedEffect
        }
        val start = pendingSeek
        pendingSeek = 0L
        error = null
        status = "Preparing stream…"
        captions = emptyList()
        val list = runCatching { Api.h5Play(activeDub, se, ep) }.getOrNull()
        if (list == null) {
            status = null
            error = "Couldn't load the stream list. Check your connection and retry."
            return@LaunchedEffect
        }
        if (list.isEmpty()) {
            streams = emptyList()
            status = null
            error = "No stream available for this title."
            return@LaunchedEffect
        }
        // Keep the same order the player will try, so a decoder failure can advance to the next
        // entry instead of stopping on the same broken quality.
        val ordered = orderCandidates(list)
        streams = ordered
        decoderRetries = 0
        setSource(ordered.first(), start)
    }

    // Offer "resume from 12:34" once the real title id is known, never for a few stray seconds.
    LaunchedEffect(detail.id) {
        if (resumeChecked || localPlayback || detail.id.isBlank()) return@LaunchedEffect
        resumeChecked = true
        val saved = Store.historyFor(detail.id) ?: return@LaunchedEffect
        if (saved.se == se && saved.ep == ep &&
            saved.position > 60_000L && saved.duration - saved.position > 30_000L
        ) {
            resumeAsk = saved
        }
    }

    // Subtitles are optional and cost two extra requests: fetch them only when CC is opened.
    LaunchedEffect(menu, activeDub, se, ep) {
        if (menu == 5 && captions.isEmpty() && !localPlayback) {
            captionsLoading = true
            captions = runCatching { Api.captionsFor(activeDub, se, ep) }.getOrDefault(emptyList())
            captionsLoading = false
            val pref = Store.subtitleLang
            if (pref != "Off" && caption == null) {
                val match = captions.firstOrNull {
                    it.lang.contains(pref, true) || it.name.contains(pref, true)
                }
                if (match != null) {
                    val pos = player.currentPosition.coerceAtLeast(0)
                    caption = match
                    streams.firstOrNull { it === current }?.let { setSource(it, pos) }
                }
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                controlsVisible = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) decoderRetries = 0
                if (state == Player.STATE_ENDED) {
                    controlsVisible = true
                    if (spec.series && ep < maxEp) nextIn = 5
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // A hardware decoder that refuses this stream used to end playback on the first
                // frame. Walk to the next quality instead; the list is ordered H.264-first.
                val code = e.errorCode
                val decoderProblem = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    code == PlaybackException.ERROR_CODE_DECODING_FAILED
                if (decoderProblem && decoderRetries < 2) {
                    val index = streams.indexOfFirst { it === current }
                    val next = streams.getOrNull(index + 1)
                    if (next != null) {
                        decoderRetries += 1
                        setSource(next, player.currentPosition.coerceAtLeast(0))
                        toast = "Switched to ${next.name}"
                        return
                    }
                }
                error = when (code) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The CDN refused this stream (HTTP error)."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network connection lost."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This device can't decode this codec. Try another quality."
                    else -> e.errorCodeName
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        var lastSave = 0L
        while (true) {
            // The player can be released between two ticks (mini → close, new title). Reading a
            // released player throws, and an exception here would leave the composition and kill
            // the process, so each tick is guarded.
            runCatching {
                positionMs = player.currentPosition.coerceAtLeast(0)
                durationMs = player.duration.takeIf { it > 0 } ?: 0L
                val now = System.currentTimeMillis()
                if (now - lastSave > 5000 && durationMs > 0 && !localPlayback && detail.id.isNotBlank()) {
                    lastSave = now
                    Store.remember(detail, se, ep, positionMs, durationMs)
                }
            }
            delay(500)
        }
    }

    LaunchedEffect(nextIn, ep) {
        if (nextIn <= 0) return@LaunchedEffect
        delay(1000)
        if (nextIn <= 1) {
            nextIn = 0
            if (Store.autoplayNext && ep < maxEp) {
                ep += 1
                pendingSeek = 0L
            }
        } else {
            nextIn -= 1
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, locked, menu) {
        if (controlsVisible && isPlaying && !locked && menu == 0) {
            delay(6000)
            controlsVisible = false
        }
    }

    // Subtitle look follows the player settings instead of Media3's default 5% white-on-nothing.
    LaunchedEffect(subSize, subBackground, playerView) {
        val view = playerView?.subtitleView ?: return@LaunchedEffect
        val bg = if (subBackground) {
            android.graphics.Color.argb(150, 0, 0, 0)
        } else {
            android.graphics.Color.TRANSPARENT
        }
        view.setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE,
                bg,
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                null,
            )
        )
        view.setApplyEmbeddedStyles(false)
        view.setFractionalTextSize(0.053f * subSize)
    }

    LaunchedEffect(scale) {
        Store.scaleMode = scale
        playerView?.resizeMode = when (scale) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            3 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    LaunchedEffect(fullscreen) {
        // Rotation must never leave the player without controls: force them back on both
        // entering and leaving fullscreen. The gesture layer is keyed on `fullscreen` too.
        controlsVisible = true
        menu = 0
        boosting = false
        // Do not force portrait on the very first frame: opening a video while the phone is in
        // landscape used to request an orientation change immediately and could bounce the
        // activity (and playback) before the first stream arrived.
        if (!orientationReady) {
            orientationReady = true
            return@LaunchedEffect
        }
        activity?.requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (window != null && fullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Back inside the player never jumps straight to Home:
    //   start / popup / exit fullscreen first, then shrink to the floating mini player.
    // In mini mode there is deliberately no handler, so the phone's back button navigates the
    // browsing pages underneath while the video keeps playing.
    BackHandler(enabled = !mini) {
        when {
            locked -> locked = false
            menu != 0 -> menu = 0
            fullscreen -> fullscreen = false
            else -> onMinimize()
        }
    }

    // Nothing modal may sit on top of the browsing pages while the mini window is up.
    LaunchedEffect(mini) {
        if (mini) {
            menu = 0
            locked = false
            controlsVisible = true
            showInfo = false
            showEpisodes = false
            sheet = emptyList()
            person = null
            resumeAsk = null
            // Home must not upgrade the floating window to a system PiP window.
            activity?.let { act ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching {
                        act.setPictureInPictureParams(Pip.params(act, autoEnter = false))
                    }
                }
            }
        } else {
            controlsVisible = true
        }
    }

    fun seekBy(delta: Long) {
        val dur = player.duration
        val upper = if (dur > 0) dur else Long.MAX_VALUE
        val target = (player.currentPosition + delta).coerceIn(0L, upper)
        player.seekTo(target)
        positionMs = target
        controlsVisible = true
    }

    fun switchQuality(item: StreamRes) {
        if (item === current || item.url.isEmpty()) return
        val pos = player.currentPosition.coerceAtLeast(0)
        menu = 0
        error = null
        status = "Switching to ${item.resolution}p…"
        setSource(item, pos)
    }

    fun switchAudio(dub: Dub) {
        if (dub.id == activeDub) return
        pendingSeek = player.currentPosition.coerceAtLeast(0)
        activeDub = dub.id
        menu = 0
        scope.launch {
            runCatching { Api.detail(dub.id) }.getOrNull()?.let { d ->
                if (d.title.isNotBlank()) detail = d
            }
        }
    }

    fun applyCaption(c: Caption?) {
        caption = c
        menu = 0
        val pos = player.currentPosition.coerceAtLeast(0)
        streams.firstOrNull { it === current }?.let { setSource(it, pos) }
    }

    fun onWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun wifiBlocked(): Boolean {
        if (!Store.wifiOnlyDownloads || onWifi()) return false
        toast = "Wi-Fi only is on · turn it off in Me → Playback"
        return true
    }

    fun loadQualities(targetEp: Int) {
        sheetEp = if (targetEp <= 0) 1 else targetEp
        sheetTitle = if (spec.series) "S$se E$sheetEp" else "Movie"
        sheetLoading = true
        sheet = emptyList()
        scope.launch {
            val list = runCatching {
                Api.h5Play(activeDub, if (spec.series) se else 0, if (spec.series) sheetEp else 0)
            }.getOrDefault(emptyList())
            sheet = list.sortedByDescending { it.resolution }
            sheetLoading = false
            if (list.isEmpty()) toast = "No downloadable stream for this title."
        }
    }

    fun enterPip() {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast = "Picture in picture needs Android 8 or newer."
            return
        }
        Pip.playing = isPlaying
        Pip.hasNext = spec.series && ep < maxEp
        Pip.title = detail.title.ifBlank { spec.title }
        runCatching { act.enterPictureInPictureMode(Pip.params(act, autoEnter = false)) }
            .onFailure { toast = "This device blocked picture-in-picture." }
    }

    // PiP belongs to the stock player: playback keeps running on Home, and the window carries
    // play/pause, next-episode and rewind buttons that talk back to this same player.
    SideEffect {
        Pip.onToggle = { if (player.isPlaying) player.pause() else player.play() }
        Pip.onNext = {
            if (spec.series && ep < maxEp) {
                ep += 1
                pendingSeek = 0L
            }
        }
        Pip.onBack10 = { seekBy(-10_000) }
    }

    DisposableEffect(Unit) {
        Pip.active = true
        onDispose {
            Pip.active = false
            Pip.onToggle = null
            Pip.onNext = null
            Pip.onBack10 = null
            activity?.let { act ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        act.setPictureInPictureParams(Pip.params(act, autoEnter = false))
                    }
                }
            }
        }
    }

    LaunchedEffect(isPlaying, ep, maxEp, detail.title, inPip) {
        Pip.playing = isPlaying
        Pip.hasNext = spec.series && ep < maxEp
        Pip.title = detail.title.ifBlank { spec.title }
        val act = activity ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                act.setPictureInPictureParams(
                    Pip.params(act, autoEnter = Pip.active && Pip.playing && !inPip && !mini)
                )
            }
        }
    }

    LaunchedEffect(inPip) {
        if (!inPip) {
            controlsVisible = true
            menu = 0
        }
    }

    // A custom Layout keeps ONE video node (and therefore one PlayerView/surface) for all three
    // shapes: top-of-page 16:9, fullscreen, and the small floating window. Rebuilding the tree
    // for mini mode would detach the surface and flash black.
    val density = LocalDensity.current
    val miniWidthPx = with(density) { 200.dp.roundToPx() }
    val miniBottomPx = with(density) { 74.dp.roundToPx() }
    val miniStartPx = with(density) { 12.dp.roundToPx() }
    Layout(
        modifier = Modifier.fillMaxSize().then(if (mini) Modifier else Modifier.background(Bg)),
        content = {
        // ------------------------------------------------------------------ video
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (mini) {
                        Modifier
                            .shadow(10.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                    } else Modifier
                )
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        playerView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // One surface owns playback gestures: tap = chrome, double tap = ±10s,
            // hold = 2x, horizontal drag = seek, vertical drag = brightness / volume.
            // `requireUnconsumed` keeps button taps from also toggling the chrome.
            if (!inPip && !mini) Box(
                Modifier.fillMaxSize().pointerInput(locked, fullscreen) {
                    if (locked) return@pointerInput
                    var lastTapAt = 0L
                    var pendingTap: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        var lastX = down.position.x
                        var lastY = down.position.y
                        var totalX = 0f
                        var totalY = 0f
                        var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume
                        var boosted = false
                        var startPos = player.currentPosition.coerceAtLeast(0)
                        var preview = startPos
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        var startBrightness =
                            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                        val startVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

                        val boostJob = scope.launch {
                            delay(420)
                            if (mode == 0 && !boosted) {
                                boosted = true
                                boosting = true
                                player.setPlaybackSpeed(2f)
                                gestureHint = "2x speed"
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.isConsumed) break
                            val dx = change.position.x - lastX
                            val dy = change.position.y - lastY
                            lastX = change.position.x
                            lastY = change.position.y
                            totalX += dx
                            totalY += dy
                            if (mode == 0 &&
                                (kotlin.math.abs(totalX) > 22f || kotlin.math.abs(totalY) > 22f)
                            ) {
                                boostJob.cancel()
                                mode = if (kotlin.math.abs(totalX) > kotlin.math.abs(totalY)) 1
                                else if (down.position.x < size.width / 2f) 2 else 3
                                startPos = player.currentPosition.coerceAtLeast(0)
                                preview = startPos
                            }
                            if (mode != 0) change.consume()
                            when (mode) {
                                1 -> {
                                    val dur = durationMs.coerceAtLeast(1L)
                                    val delta = (totalX / size.width * 120_000L).toLong()
                                    preview = (startPos + delta).coerceIn(0L, dur)
                                    gestureHint = "${fmtTime(preview)} / ${fmtTime(dur)}"
                                }
                                2 -> {
                                    val b = (startBrightness - totalY / size.height * 1.4f)
                                        .coerceIn(0.02f, 1f)
                                    activity?.window?.let { w ->
                                        w.attributes = w.attributes.apply { screenBrightness = b }
                                    }
                                    gestureHint = "Brightness ${(b * 100).toInt()}%"
                                }
                                3 -> {
                                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                                    val v = (startVolume - totalY / size.height * max * 1.2f)
                                        .toInt().coerceIn(0, max)
                                    am?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    gestureHint = "Volume ${(v * 100 / max)}%"
                                }
                            }
                        }

                        boostJob.cancel()
                        if (boosted) {
                            boosting = false
                            player.setPlaybackSpeed(speed)
                            gestureHint = null
                        }
                        when (mode) {
                            1 -> {
                                player.seekTo(preview)
                                positionMs = preview
                                controlsVisible = true
                            }
                            2, 3 -> controlsVisible = true
                            else -> {
                                if (!boosted) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapAt < 320) {
                                        pendingTap?.cancel()
                                        lastTapAt = 0L
                                        seekBy(if (down.position.x < size.width / 2f) -10_000 else 10_000)
                                    } else {
                                        lastTapAt = now
                                        pendingTap?.cancel()
                                        pendingTap = scope.launch {
                                            delay(260)
                                            if (menu != 0) {
                                                menu = 0
                                                controlsVisible = true
                                            } else {
                                                controlsVisible = !controlsVisible
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        dragMode = 0
                        gestureHint = null
                    }
                }
            )

            if (buffering && status == null && error == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.align(Alignment.Center).size(34.dp),
                )
            }
            status?.let {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.White, fontSize = 11.sp)
                }
            }

            gestureHint?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 38.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            if (locked && !inPip && !mini) {
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x99000000))
                        .clickable { locked = false }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ls_ic_player_unlock),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Tap to Unlock", color = Color.White, fontSize = 10.sp)
                }
            }

            if (nextIn > 0 && !inPip && !mini) {
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .widthIn(max = 210.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xE616181C))
                        .padding(10.dp),
                ) {
                    Text("Next episode in ${nextIn}s", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallAction("Play now") { nextIn = 0; ep += 1; pendingSeek = 0L }
                        SmallAction("Cancel") { nextIn = 0 }
                    }
                }
            }

            if (controlsVisible && !locked && !inPip && !mini) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                        .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(34.dp).clickable {
                            // In fullscreen the arrow only drops back to portrait, exactly like
                            // the stock player; a second press (or back) shrinks to mini.
                            if (fullscreen) fullscreen = false else onMinimize()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_nav_back),
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.title.ifBlank { spec.title },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = buildList {
                            if (spec.series) add("S${"%02d".format(se)} E${"%02d".format(ep)}")
                            if (localPlayback) add("downloaded")
                            else {
                                dubs.firstOrNull { it.id == activeDub }?.let { add(langLabel(it)) }
                                current?.let { add("${it.resolution}p ${codecLabel(it.codec)}") }
                            }
                            caption?.let { add("CC ${it.name}") }
                        }.joinToString("  ·  ")
                        if (sub.isNotEmpty()) {
                            Text(sub, color = TextDim, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    Box(
                        Modifier.size(32.dp).clickable { menu = if (menu == 5) 0 else 5 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "CC",
                            color = if (caption != null) AccentSoft else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(
                        Modifier.size(32.dp).clickable { enterPip() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_player_pip),
                            contentDescription = "Picture in picture",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Box(
                        Modifier.size(32.dp).clickable {
                            fullscreen = !fullscreen
                            controlsVisible = true
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tv_fullscreen),
                            contentDescription = "Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Box(
                        Modifier.size(32.dp).clickable { menu = if (menu == 4) 0 else 4 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_player_setting),
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }

                // centre controls
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clickable { seekBy(-10_000) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_player_backward_10),
                            contentDescription = "Back 10s",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0x4D000000))
                            .clickable {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                            ),
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Box(
                        Modifier.size(40.dp).clickable { seekBy(10_000) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_player_forward_10),
                            contentDescription = "Forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x66000000))
                        .clickable {
                            locked = true
                            controlsVisible = false
                        }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ls_ic_player_lock),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Tap to Lock", color = Color.White, fontSize = 10.sp)
                }

                // bottom bar
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE6000000))))
                        .padding(start = 10.dp, end = 10.dp, top = 34.dp, bottom = 6.dp),
                ) {
                    Column {
                        SeekBar(
                            position = positionMs,
                            duration = durationMs,
                            onSeek = { target ->
                                player.seekTo(target)
                                positionMs = target
                                controlsVisible = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fmtTime(positionMs),
                                color = Color.White,
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                fmtTime(durationMs),
                                color = Color(0xB3FFFFFF),
                                fontSize = 10.sp,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            Modifier.size(30.dp).clickable {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                                ),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        if (spec.series && ep < maxEp) {
                            Box(
                                Modifier.size(30.dp).clickable {
                                    ep += 1
                                    pendingSeek = 0L
                                    controlsVisible = true
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play_next),
                                    contentDescription = "Next episode",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (speed == 1f) "1x" else "${speed}x",
                            color = if (speed == 1f) Color.White else AccentSoft,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { menu = if (menu == 3) 0 else 3 }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                        )
                        Text(
                            current?.let { "${it.resolution}P" } ?: "AUTO",
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { menu = if (menu == 1) 0 else 1 }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                        )
                        if (dubs.size > 1) {
                            Text(
                                dubs.firstOrNull { it.id == activeDub }?.let { langLabel(it) } ?: "Audio",
                                color = Color.White,
                                fontSize = 10.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { menu = if (menu == 2) 0 else 2 }
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                            )
                        }
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { menu = if (menu == 6) 0 else 6 }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_player_screen),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(SCALES.getOrElse(scale) { "Fit" }, color = Color.White, fontSize = 10.sp)
                        }
                    }
                }

                when (menu) {
                    1 -> PanelOverlay("Quality", Alignment.BottomEnd) {
                        streams.forEach { item ->
                            PanelLine(
                                label = "${item.resolution}p",
                                tag = listOf(codecLabel(item.codec), sizeText(item.size))
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                selected = item === current,
                            ) { switchQuality(item) }
                        }
                    }
                    2 -> PanelOverlay("Audio", Alignment.BottomEnd) {
                        dubs.forEach { dub ->
                            PanelLine(langLabel(dub), if (dub.original) "original" else "", dub.id == activeDub) {
                                switchAudio(dub)
                            }
                        }
                    }
                    3 -> PanelOverlay("Speed", Alignment.BottomEnd) {
                        SPEEDS.forEach { s ->
                            PanelLine(if (s == 1f) "Normal" else "${s}x", "", speed == s) {
                                speed = s
                                Store.speed = s
                                player.setPlaybackSpeed(s)
                                menu = 0
                            }
                        }
                    }
                    5 -> PanelOverlay("Subtitles", Alignment.TopEnd) {
                        PanelLine("Off", "", caption == null) { applyCaption(null) }
                        captions.forEach { c ->
                            PanelLine(c.name.ifBlank { c.lang }, c.lang, caption?.id == c.id) { applyCaption(c) }
                        }
                        if (captionsLoading) {
                            PanelLine("Loading tracks…", "", false) {}
                        } else if (captions.isEmpty()) {
                            PanelLine("No subtitle track", "", false) {}
                        }
                        PanelLine(
                            "Text size",
                            when {
                                subSize < 0.95f -> "small"
                                subSize > 1.1f -> "large"
                                else -> "normal"
                            },
                            false,
                        ) {
                            val next = when {
                                subSize < 0.95f -> 1f
                                subSize < 1.1f -> 1.25f
                                else -> 0.85f
                            }
                            subSize = next
                            Store.subSize = next
                        }
                        PanelLine("Background", if (subBackground) "on" else "off", subBackground) {
                            subBackground = !subBackground
                            Store.subBackground = subBackground
                        }
                    }
                    6 -> PanelOverlay("Screen", Alignment.BottomEnd) {
                        SCALES.forEachIndexed { i, name ->
                            PanelLine(name, "", scale == i) { scale = i; menu = 0 }
                        }
                    }
                    else -> Unit
                }

                if (menu == 4) {
                    PanelOverlay("Settings", Alignment.TopEnd) {
                        PanelLine("Fullscreen", "", fullscreen) { fullscreen = !fullscreen; menu = 0 }
                        PanelLine("Lock screen", "", locked) { locked = true; menu = 0 }
                        PanelLine("Picture in picture", "auto", false) { enterPip(); menu = 0 }
                        PanelLine("Auto play next", "", Store.autoplayNext) {
                            Store.autoplayNext = !Store.autoplayNext
                        }
                        PanelLine("Data saver", "480p", Store.dataSaver) {
                            Store.dataSaver = !Store.dataSaver
                        }
                        PanelLine("Reload stream", "", false) { retryKey++; menu = 0 }
                    }
                }
            }

            if (!mini) error?.let { msg ->
                Column(
                    Modifier.fillMaxSize().background(Color(0xE6000000)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(msg, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction("Retry") {
                            error = null
                            retryKey++
                        }
                        streams.firstOrNull { it !== current }?.let { alt ->
                            SmallAction("Try ${alt.resolution}p") {
                                error = null
                                switchQuality(alt)
                            }
                        }
                        SmallAction("Fullscreen") { fullscreen = true }
                    }
                }
            }

            // Mini window chrome: tap anywhere to go back to the full player, X to stop and
            // release it. Everything else (controls, gestures, sheets) stays out of the way.
            if (mini) {
                Box(Modifier.fillMaxSize().clickable(onClick = onExpand))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x00000000), Color(0xCC000000))
                            )
                        ),
                )
                Text(
                    detail.title.ifBlank { spec.title },
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 30.dp, bottom = 6.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xB3000000))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ls_ic_close_20),
                        contentDescription = "Close mini player",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
                // Thin progress line, same accent green as the full player's seek bar.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x33000000)),
                ) {
                    val frac = if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(2.dp)
                            .background(Accent),
                    )
                }
            }
        }

        // ------------------------------------------------------------------ details
        if (!fullscreen && !inPip && !mini) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        detail.title.ifBlank { spec.title },
                        color = TextMain,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Info ›",
                        color = TextMute,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { showInfo = true }.padding(4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                val meta = buildList {
                    if (detail.rating.isNotBlank() && detail.rating != "0") add("★ ${detail.rating}")
                    if (detail.year.isNotBlank()) add(detail.year)
                    if (detail.country.isNotBlank()) add(detail.country)
                    if (detail.genre.isNotBlank()) add(detail.genre.substringBefore(","))
                    if (detail.contentRating.isNotBlank()) add(detail.contentRating)
                    if (spec.series && seasons.isNotEmpty()) add("${seasons.size} seasons")
                }.joinToString("  |  ")
                if (meta.isNotBlank()) {
                    Text(meta, color = TextMute, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }

                if (detail.desc.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        detail.desc,
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    var liked by remember(detail.id) { mutableStateOf(Store.inList(detail.id)) }
                    IconAction(
                        icon = R.drawable.ic_movie_like,
                        label = if (liked) "Liked" else "Like",
                        tint = if (liked) AccentSoft else TextDim,
                    ) {
                        liked = Store.toggleList(detail)
                        toast = if (liked) "Added to My List" else "Removed from My List"
                    }
                    IconAction(R.drawable.ic_me_my_list, "My list") {
                        val added = Store.toggleList(detail)
                        toast = if (added) "Added to My List" else "Removed from My List"
                    }
                    IconAction(R.drawable.ic_movie_share, "Share") {
                        scope.launch {
                            val slug = runCatching { Api.detailPath(spec.baseId) }.getOrNull().orEmpty()
                            val link = if (slug.isEmpty()) detail.title else "https://movie-box.co/movies/$slug"
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                val deep = "mbox://subject/${detail.id.ifBlank { spec.baseId }}"
                                putExtra(Intent.EXTRA_TEXT, "${detail.title}\n$link\n$deep")
                            }
                            runCatching { ctx.startActivity(Intent.createChooser(send, "Share")) }
                        }
                    }
                    if (detail.trailer.isNotBlank()) {
                        IconAction(R.drawable.ic_player_play, "Trailer") {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detail.trailer)))
                            }.onFailure { toast = "No app can open the trailer." }
                        }
                    }
                    IconAction(R.drawable.ic_movie_download, "Download") {
                        loadQualities(if (spec.series) ep else 0)
                    }
                }

                toast?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = AccentSoft, fontSize = 11.sp)
                }

                Spacer(Modifier.height(14.dp))
                Text("Resources", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                if (dubs.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(dubs.size) { i ->
                            val d = dubs[i]
                            LangChip(langLabel(d), d.id == activeDub) { switchAudio(d) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (detail.subtitleLangs.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Subtitles", color = TextMute, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            caption?.name ?: detail.subtitleLangs.joinToString(", "),
                            color = TextDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    controlsVisible = true
                                    menu = 5
                                },
                        )
                        Text("Change", color = AccentSoft, fontSize = 11.sp,
                            modifier = Modifier.clickable { controlsVisible = true; menu = 5 })
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (spec.series) {
                    // Two outlined pills exactly like fragment_episode.xml: the season picker
                    // and the episode picker. Both open the official-style "All Episodes" sheet.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (seasons.isNotEmpty()) {
                            DropDown("Season " + "%02d".format(se)) { showEpisodes = true }
                        }
                        DropDown("All Episodes") { showEpisodes = true }
                    }
                    Spacer(Modifier.height(10.dp))

                    EpisodeChips(maxEp, ep) { target ->
                        if (target > 0) {
                            ep = target
                            pendingSeek = 0L
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (ep > 0) {
                        EpisodeLine(
                            ep = ep,
                            active = true,
                            onPlay = { pendingSeek = 0L },
                            onDownload = { loadQualities(ep) },
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent)
                            .clickable { retryKey++ }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▶  Play", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (detail.staff.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Cast & Crew", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(detail.staff.size) { i ->
                            PersonCard(detail.staff[i]) { person = detail.staff[i] }
                        }
                    }
                }

                if (recommendations.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("You may also like", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recommendations.size) { i ->
                            val rec = recommendations[i]
                            Column(
                                Modifier.width(96.dp).clickable {
                                    player.pause()
                                    onOpen(rec)
                                }
                            ) {
                                AsyncImage(
                                    model = rec.cover,
                                    contentDescription = rec.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Surface2),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    rec.title,
                                    color = TextMain,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        when {
            mini -> {
                val w = miniWidthPx.coerceAtMost(width)
                val h = (w * 9 / 16).coerceAtMost(height)
                val video = measurables.first().measure(Constraints.fixed(w, h))
                layout(width, height) {
                    video.place(
                        x = miniStartPx.coerceAtMost((width - w).coerceAtLeast(0)),
                        y = (height - h - miniBottomPx).coerceAtLeast(0),
                    )
                }
            }

            fullscreen || inPip -> {
                val video = measurables.first().measure(Constraints.fixed(width, height))
                layout(width, height) { video.place(0, 0) }
            }

            else -> {
                val videoHeight = width * 9 / 16
                val video = measurables.first().measure(Constraints.fixed(width, videoHeight))
                val details = measurables.getOrNull(1)
                    ?.measure(Constraints.fixed(width, (height - videoHeight).coerceAtLeast(0)))
                layout(width, height) {
                    video.place(0, 0)
                    details?.place(0, videoHeight)
                }
            }
        }
    }

    // ------------------------------------------------------------------ person sheet
    if (!mini) person?.let { p ->
        StaffSheet(
            staff = p,
            onDismiss = { person = null },
            onOpen = { rec ->
                player.pause()
                onOpen(rec)
            },
        )
    }

    // ------------------------------------------------------------------ detail page
    if (showInfo && !mini) {
        DetailPage(
            subject = detail,
            seasonCount = seasons.size,
            episodeLabel = if (spec.series) "S${"%02d".format(se)} E${"%02d".format(ep)}" else "Movie",
            onClose = { showInfo = false },
            onPlay = {
                showInfo = false
                controlsVisible = true
            },
            onDownload = {
                showInfo = false
                loadQualities(if (spec.series) ep else 0)
            },
            onPerson = { person = it },
        )
    }

    // ------------------------------------------------------------------ resume prompt
    if (!mini) resumeAsk?.let { saved ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { resumeAsk = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF17191E))
                    .padding(16.dp),
            ) {
                Text("Continue watching?", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    (detail.title.ifBlank { spec.title }) + " · " + fmtTime(saved.position) +
                        if (spec.series) " · S${saved.se} E${saved.ep}" else "",
                    color = TextMute,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("Start over") {
                        player.seekTo(0L)
                        positionMs = 0L
                        resumeAsk = null
                    }
                    SmallAction("Resume from " + fmtTime(saved.position)) {
                        player.seekTo(saved.position)
                        positionMs = saved.position
                        controlsVisible = true
                        resumeAsk = null
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ episode sheet
    //
    // Rebuilt from dialog_episode_list.xml + item_video_detail_episode.xml: bottom sheet with an
    // 8dp top radius, 18sp bold title, close button, hairline divider, scrollable season tabs
    // (green selected text) and 44dp square episode tiles. Selected = blue→green 20% gradient,
    // normal = white 10%; radius 4dp throughout.
    if (showEpisodes && !mini) {
        val watched = remember(se, detail.id) {
            Store.history().filter { it.id == detail.id && it.se == se }.associateBy { it.ep }
        }
        val seasonRow = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(se) {
            val index = seasons.indexOfFirst { it.se == se }
            if (index >= 0) seasonRow.animateScrollToItem(index)
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showEpisodes = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(Color(0xFF383A40))
                        .navigationBarsPadding(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 6.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "All Episodes",
                                color = TextMain,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                (detail.title.ifBlank { spec.title }) +
                                    if (spec.series) "  ·  Season ${"%02d".format(se)}" else "",
                                color = TextMute,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .clickable { showEpisodes = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ls_ic_close_20),
                                contentDescription = "Close",
                                tint = Color(0xCCFFFFFF),
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x1AFFFFFF)))

                    if (seasons.size > 1) {
                        LazyRow(
                            state = seasonRow,
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(seasons.size) { i ->
                                val season = seasons[i]
                                val active = season.se == se
                                Column(
                                    Modifier
                                        .clickable {
                                            if (season.se != se) {
                                                se = season.se
                                                ep = 1
                                                pendingSeek = 0L
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "Season ${season.se}",
                                        color = if (active) AccentSoft else Color(0xCCFFFFFF),
                                        fontSize = 14.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    Spacer(Modifier.height(7.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(if (active) Accent else Color.Transparent)
                                    )
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x1AFFFFFF)))
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(maxEp) { i ->
                            val e = i + 1
                            val active = e == ep
                            val seen = watched[e]
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .then(
                                        if (active) {
                                            Modifier
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(Color(0x401CB7FF), Color(0x402FF58B))
                                                    )
                                                )
                                                .border(1.dp, Color(0x662FF58B), RoundedCornerShape(4.dp))
                                        } else {
                                            Modifier.background(Color(0x1AFFFFFF))
                                        }
                                    )
                                    .clickable {
                                        ep = e
                                        pendingSeek = 0L
                                        showEpisodes = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "%02d".format(e),
                                    color = if (active) AccentSoft else Color(0xCCFFFFFF),
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (seen != null && !active) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 3.dp)
                                            .size(3.dp)
                                            .clip(CircleShape)
                                            .background(AccentSoft)
                                    )
                                }
                                // Per-episode download, the small icon the stock tile puts in
                                // its top-right corner.
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .size(14.dp)
                                        .clickable { loadQualities(e) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_movie_download),
                                        contentDescription = "Download episode $e",
                                        tint = if (active) AccentSoft else Color(0x99FFFFFF),
                                        modifier = Modifier.size(9.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ download sheet
    if (!mini && (sheet.isNotEmpty() || sheetLoading)) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { if (!sheetLoading) sheet = emptyList() }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF17191E))
                    .padding(16.dp),
            ) {
                Text("Download · $sheetTitle", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (sheetLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Fetching qualities…", color = TextMute, fontSize = 12.sp)
                    }
                }
                sheet.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (wifiBlocked()) return@clickable
                                Downloads.start(
                                    ctx,
                                    key = "${spec.baseId}-${if (spec.series) se else 0}-" +
                                        "${if (spec.series) sheetEp else 0}-${s.resolution}-$activeDub",
                                    title = detail.title.ifBlank { spec.title },
                                    subtitle = if (spec.series) "S${se}E$sheetEp ${s.resolution}p"
                                    else "${s.resolution}p",
                                    stream = s,
                                )
                                sheet = emptyList()
                                toast = "Download started · ${s.resolution}p"
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${s.resolution}p", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(codecLabel(s.codec), color = TextMute, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(sizeText(s.size), color = TextMute, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_movie_download),
                            contentDescription = null,
                            tint = AccentSoft,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (spec.series && sheet.isNotEmpty() && !sheetLoading) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Download the rest of this season (" +
                            "${(maxEp - ep + 1).coerceAtLeast(0)} episodes)",
                        color = AccentSoft,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            if (wifiBlocked()) return@clickable
                            val pick = sheet.firstOrNull()
                            if (pick != null) {
                                scope.launch {
                                    for (e in ep..maxEp.coerceAtMost(ep + 39)) {
                                        val list = runCatching { Api.h5Play(activeDub, se, e) }
                                            .getOrDefault(emptyList())
                                        val same = list.firstOrNull { it.resolution == pick.resolution }
                                            ?: list.firstOrNull() ?: continue
                                        Downloads.start(
                                            ctx,
                                            key = "${spec.baseId}-$se-$e-${same.resolution}-$activeDub",
                                            title = detail.title.ifBlank { spec.title },
                                            subtitle = "S${se}E$e ${same.resolution}p",
                                            stream = same,
                                        )
                                        delay(400)
                                    }
                                    toast = "Queued the rest of the season"
                                }
                            }
                            sheet = emptyList()
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- pieces

@Composable
private fun IconAction(icon: Int, label: String, tint: Color = TextDim, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextMute, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextMute, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun PanelOverlay(title: String, align: Alignment, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(min = 150.dp, max = 240.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xF214161A))
                .padding(vertical = 6.dp, horizontal = 10.dp),
        ) {
            Text(title, color = TextMute, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
            content()
        }
    }
}

@Composable
private fun PanelLine(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) AccentSoft else Color.White,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (tag.isNotEmpty()) {
            Spacer(Modifier.width(5.dp))
            Text(tag, color = TextMute, fontSize = 9.sp)
        }
        Spacer(Modifier.weight(1f))
        if (selected) Text("✓", color = AccentSoft, fontSize = 11.sp)
    }
}

@Composable
private fun LangChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) AccentSoft else TextMute,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF10241A) else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** 32dp outlined dropdown pill from item_layout style: radius 4, white 20% stroke, 12sp. */
@Composable
private fun DropDown(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xCCFFFFFF), fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Icon(
            painter = painterResource(R.drawable.ic_arrow_down),
            contentDescription = null,
            tint = Color(0xCCFFFFFF),
            modifier = Modifier.size(10.dp),
        )
    }
}

@Composable
private fun EpisodeChips(maxEp: Int, selected: Int, onSelect: (Int) -> Unit) {
    if (maxEp <= 0) return
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(selected, maxEp) {
        if (selected > 0) state.animateScrollToItem((selected - 1).coerceIn(0, maxEp - 1))
    }
    androidx.compose.foundation.lazy.LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(end = 4.dp),
    ) {
        items(maxEp) { i ->
            val e = i + 1
            // item_video_detail_episode.xml: 44dp tall, 4dp corners, white 10% normal and a
            // blue→green 20% gradient when selected.
            Box(
                Modifier
                    .size(46.dp, 44.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (selected == e) {
                            Modifier
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0x401CB7FF), Color(0x402FF58B))
                                    )
                                )
                                .border(1.dp, Color(0x662FF58B), RoundedCornerShape(4.dp))
                        } else {
                            Modifier.background(Color(0x1AFFFFFF))
                        }
                    )
                    .clickable { onSelect(e) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "%02d".format(e),
                    color = if (selected == e) AccentSoft else Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                    fontWeight = if (selected == e) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun EpisodeLine(ep: Int, active: Boolean, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xFF11221A) else Surface1)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "E" + "%02d".format(ep),
            color = if (active) AccentSoft else TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onPlay),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (active) "Now playing" else "Tap to play",
            color = TextMute,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f).clickable(onClick = onPlay),
        )
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Surface2)
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_movie_download),
                contentDescription = "Download episode",
                tint = AccentSoft,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_player_play),
                contentDescription = "Play episode",
                tint = Color.Black,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** Thin brand seek bar with a draggable handle, matching the stock player instead of Material's slider. */
@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dur = duration.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val frac = if (dragging) dragFrac else (position.toFloat() / dur).coerceIn(0f, 1f)

    Canvas(
        modifier
            .height(26.dp)
            .pointerInput(dur) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    dragFrac = (down.position.x / size.width).coerceIn(0f, 1f)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position.x != down.position.x) {
                            change.consume()
                            dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    onSeek((dragFrac * dur).toLong())
                    dragging = false
                }
            },
    ) {
        val cy = size.height / 2f
        val track = 3.dp.toPx()
        val radius = track / 2f
        drawRoundRect(
            color = Color(0x3DFFFFFF),
            topLeft = Offset(0f, cy - radius),
            size = Size(size.width, track),
            cornerRadius = CornerRadius(radius, radius),
        )
        if (frac > 0f) {
            drawRoundRect(
                color = Accent,
                topLeft = Offset(0f, cy - radius),
                size = Size(size.width * frac, track),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
        drawCircle(
            color = if (dragging) AccentSoft else Color.White,
            radius = if (dragging) 6.dp.toPx() else 4.5.dp.toPx(),
            center = Offset(size.width * frac, cy),
        )
    }
}
