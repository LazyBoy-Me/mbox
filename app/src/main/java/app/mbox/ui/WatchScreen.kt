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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.mbox.Api
import app.mbox.Caption
import app.mbox.Downloads
import app.mbox.Dub
import app.mbox.PlaySpec
import app.mbox.R
import app.mbox.Season
import app.mbox.Staff
import app.mbox.Store
import app.mbox.StreamRes
import app.mbox.Subject
import coil.compose.AsyncImage
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
fun WatchScreen(spec: PlaySpec, onBack: () -> Unit, onOpen: (Subject) -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.activityOrNull() }
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(ctx).build() }

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
    var expanded by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showSeasonMenu by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------ playback
    var streams by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var current by remember { mutableStateOf<StreamRes?>(null) }
    var status by remember { mutableStateOf<String?>("Preparing stream…") }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var pendingSeek by remember { mutableStateOf(0L) }

    // ------------------------------------------------------------------ chrome
    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(0) } // 1 quality 2 audio 3 speed 4 settings 5 subtitle
    var speed by remember { mutableStateOf(Store.speed) }
    var scale by remember { mutableStateOf(Store.scaleMode) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }

    // gestures
    var dragMode by remember { mutableStateOf(0) } // 1 seek, 2 brightness, 3 volume
    var previewMs by remember { mutableStateOf(0L) }
    var startPos by remember { mutableStateOf(0L) }
    var gestureHint by remember { mutableStateOf<String?>(null) }

    // next-episode countdown
    var nextIn by remember { mutableStateOf(0) }

    // download sheet
    var sheet by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var sheetLoading by remember { mutableStateOf(false) }
    var sheetEp by remember { mutableStateOf(1) }
    var sheetTitle by remember { mutableStateOf("") }

    val localPath = spec.localPath
    val localPlayback = localPath.isNotBlank()
    val maxEp = seasons.firstOrNull { it.se == se }?.maxEp ?: spec.maxEp

    fun setSource(item: StreamRes, startMs: Long) {
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
        player.setMediaSource(
            ProgressiveMediaSource.Factory(ds).createMediaSource(builder.build())
        )
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
        player.setPlaybackSpeed(speed)
        player.playWhenReady = true
        current = item
        error = null
        status = null
    }

    fun setLocal(path: String) {
        val base = DefaultHttpDataSource.Factory().setUserAgent(Api.PLAYER_UA)
        player.setMediaSource(
            ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx, base))
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(File(path))))
        )
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.playWhenReady = true
        status = null
    }

    fun orderCandidates(list: List<StreamRes>): List<StreamRes> {
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
        streams = list
        if (list.isEmpty()) {
            status = null
            error = "No stream available for this title."
            return@LaunchedEffect
        }
        setSource(orderCandidates(list).first(), start)
        // subtitles load after the stream, they are optional
        runCatching { Api.captionsFor(activeDub, se, ep) }.onSuccess { captions = it }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    controlsVisible = true
                    if (spec.series && ep < maxEp) nextIn = 5
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
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
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            val now = System.currentTimeMillis()
            if (now - lastSave > 5000 && durationMs > 0 && !localPlayback && detail.id.isNotBlank()) {
                lastSave = now
                Store.remember(detail, se, ep, positionMs, durationMs)
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

    LaunchedEffect(controlsVisible, isPlaying, locked) {
        if (controlsVisible && isPlaying && !locked) {
            delay(4000)
            controlsVisible = false
            menu = 0
        }
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

    BackHandler {
        when {
            locked -> locked = false
            menu != 0 -> menu = 0
            fullscreen -> fullscreen = false
            else -> onBack()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            runCatching {
                activity.enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // ------------------------------------------------------------------ video
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
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

            // tap = chrome, double tap = ±10s
            Box(
                Modifier.fillMaxSize().pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            if (locked) return@detectTapGestures
                            if (menu != 0) menu = 0 else controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { off ->
                            if (locked) return@detectTapGestures
                            if (off.x < size.width / 2f) seekBy(-10_000) else seekBy(10_000)
                        },
                    )
                }
            )

            // horizontal = seek, left/right vertical = brightness / volume
            Box(
                Modifier.fillMaxSize().pointerInput(locked) {
                    if (locked) return@pointerInput
                    var dx = 0f
                    var dy = 0f
                    var startBrightness = 0.5f
                    var startVolume = 0
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    detectDragGestures(
                        onDragStart = { o ->
                            dx = 0f
                            dy = 0f
                            dragMode = 0
                            startPos = player.currentPosition.coerceAtLeast(0)
                            previewMs = startPos
                            activity?.window?.attributes?.let { startBrightness = it.screenBrightness.takeIf { b -> b >= 0f } ?: 0.5f }
                            startVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                            gestureHint = null
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            dx += drag.x
                            dy += drag.y
                            if (dragMode == 0 && (kotlin.math.abs(dx) > 22f || kotlin.math.abs(dy) > 22f)) {
                                dragMode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 1
                                else if (change.position.x < size.width / 2f) 2 else 3
                            }
                            when (dragMode) {
                                1 -> {
                                    val dur = durationMs.coerceAtLeast(1L)
                                    val delta = (dx / size.width * 120_000L).toLong()
                                    previewMs = (startPos + delta).coerceIn(0, dur)
                                    gestureHint = "${fmtTime(previewMs)} / ${fmtTime(dur)}"
                                }
                                2 -> {
                                    val b = (startBrightness - dy / size.height * 1.4f).coerceIn(0.02f, 1f)
                                    activity?.window?.let { w ->
                                        w.attributes = w.attributes.apply { screenBrightness = b }
                                    }
                                    gestureHint = "Brightness ${(b * 100).toInt()}%"
                                }
                                3 -> {
                                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                                    val v = (startVolume - dy / size.height * max * 1.2f).toInt().coerceIn(0, max)
                                    am?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    gestureHint = "Volume ${(v * 100 / max)}%"
                                }
                            }
                        },
                        onDragEnd = {
                            if (dragMode == 1) {
                                player.seekTo(previewMs)
                                positionMs = previewMs
                            }
                            dragMode = 0
                            gestureHint = null
                        },
                        onDragCancel = {
                            dragMode = 0
                            gestureHint = null
                        },
                    )
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

            if (locked) {
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

            if (nextIn > 0) {
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

            if (controlsVisible && !locked) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                        .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(34.dp).clickable { onBack() },
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
                    val dur = durationMs.coerceAtLeast(1L)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime(if (seeking) seekValue.toLong() else positionMs), color = Color.White, fontSize = 9.sp)
                        Slider(
                            value = (if (seeking) seekValue else positionMs.toFloat()).coerceIn(0f, dur.toFloat()),
                            onValueChange = {
                                seeking = true
                                seekValue = it
                            },
                            onValueChangeFinished = {
                                player.seekTo(seekValue.toLong())
                                positionMs = seekValue.toLong()
                                seeking = false
                            },
                            valueRange = 0f..dur.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                                inactiveTrackColor = Color(0x55FFFFFF),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        )
                        Text(fmtTime(durationMs), color = Color.White, fontSize = 9.sp)
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
                            "1.0x".replace("1.0", if (speed == 1f) "1" else speed.toString()),
                            color = Color.White,
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
                        if (captions.isEmpty()) PanelLine("No subtitle track", "", false) {}
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
                        PanelLine("Picture in picture", "beta", false) { enterPip(); menu = 0 }
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

            error?.let { msg ->
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
        }

        // ------------------------------------------------------------------ details
        if (!fullscreen) {
            Column(
                Modifier
                    .weight(1f)
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
                                putExtra(Intent.EXTRA_TEXT, "${detail.title}\n$link")
                            }
                            runCatching { ctx.startActivity(Intent.createChooser(send, "Share")) }
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (spec.series && seasons.isNotEmpty()) {
                        DropDown("Season " + "%02d".format(se)) { showSeasonMenu = !showSeasonMenu }
                    }
                    DropDown("${maxEp.coerceAtLeast(1)} episodes") { showSeasonMenu = !showSeasonMenu }
                }
                if (showSeasonMenu && seasons.isNotEmpty()) {
                    MenuBlock {
                        seasons.forEach { s ->
                            MenuLine("Season " + "%02d".format(s.se), s.se == se) {
                                se = s.se
                                ep = 1
                                pendingSeek = 0L
                                showSeasonMenu = false
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (spec.series) {
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
                            StaffCard(detail.staff[i])
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
    }

    // ------------------------------------------------------------------ info sheet
    if (showInfo) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showInfo = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF17191E))
                    .padding(16.dp),
            ) {
                Text(detail.title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                InfoLine("Type", if (spec.series) "TV Series" else "Movie")
                InfoLine("Year", detail.year)
                InfoLine("Country", detail.country)
                InfoLine("Language", detail.language)
                InfoLine("Genre", detail.genre)
                InfoLine("Duration", detail.duration)
                InfoLine("Rating", if (detail.rating.isBlank()) "" else "${detail.rating} / 10")
                InfoLine("Content rating", detail.contentRating)
                InfoLine("Views", detail.viewers)
                if (detail.aka.isNotBlank()) InfoLine("Also known as", detail.aka)
                Spacer(Modifier.height(10.dp))
                Text("Close", color = AccentSoft, fontSize = 13.sp,
                    modifier = Modifier.clickable { showInfo = false }.padding(4.dp))
            }
        }
    }

    // ------------------------------------------------------------------ download sheet
    if (sheet.isNotEmpty() || sheetLoading) {
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
                        "Queue episodes 1–20 of this season",
                        color = AccentSoft,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            val pick = sheet.firstOrNull()
                            if (pick != null) {
                                scope.launch {
                                    for (e in 1..maxEp.coerceAtMost(20)) {
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
                                    toast = "Queued ${maxEp.coerceAtMost(20)} episodes"
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
private fun StaffCard(s: Staff) {
    Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = s.avatar,
            contentDescription = s.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Surface2),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            s.name,
            color = TextMain,
            fontSize = 10.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        if (s.character.isNotBlank()) {
            Text(
                s.character,
                color = TextMute,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
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

@Composable
private fun DropDown(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextDim, fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        Icon(
            painter = painterResource(R.drawable.ic_arrow_down),
            contentDescription = null,
            tint = TextMute,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun MenuBlock(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface1)
            .padding(vertical = 2.dp),
        content = { content() },
    )
}

@Composable
private fun MenuLine(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (selected) AccentSoft else TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = AccentSoft, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeChips(maxEp: Int, selected: Int, onSelect: (Int) -> Unit) {
    if (maxEp <= 0) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(maxEp) { i ->
            val e = i + 1
            Box(
                Modifier
                    .size(40.dp, 30.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected == e) Color(0xFF10241A) else Surface1)
                    .clickable { onSelect(e) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "%02d".format(e),
                    color = if (selected == e) AccentSoft else TextMute,
                    fontSize = 11.sp,
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
