package app.mbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.mbox.Downloads
import app.mbox.Dub
import app.mbox.PlaySpec
import app.mbox.Season
import app.mbox.StreamRes
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

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

/**
 * One screen for everything: video on top (portrait by default), the stock app's resource block
 * underneath (audio languages, seasons, horizontal episode list), then suggestions.
 * Fullscreen flips it into a landscape player with the same controls.
 */
@OptIn(UnstableApi::class)
@Composable
fun WatchScreen(spec: PlaySpec, onBack: () -> Unit, onOpen: (Subject) -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.activityOrNull() }
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(ctx).build() }

    // --- catalog state
    var detail by remember { mutableStateOf(Subject(title = spec.title, type = if (spec.series) 2 else 1)) }
    var dubs by remember { mutableStateOf<List<Dub>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var activeDub by remember { mutableStateOf(spec.dubId.ifBlank { spec.baseId }) }
    var se by remember { mutableStateOf(spec.se) }
    var ep by remember { mutableStateOf(if (spec.ep <= 0) 1 else spec.ep) }
    var recommendations by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var inList by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSeasonMenu by remember { mutableStateOf(false) }

    // --- playback state
    var streams by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var current by remember { mutableStateOf<StreamRes?>(null) }
    var status by remember { mutableStateOf<String?>("Preparing stream…") }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var pendingSeek by remember { mutableStateOf(0L) }

    // --- chrome
    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(0) } // 1 quality, 2 audio, 3 speed, 4 settings
    var speed by remember { mutableStateOf(1f) }
    var fill by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }

    // --- download sheet
    var sheet by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var sheetLoading by remember { mutableStateOf(false) }
    var sheetEp by remember { mutableStateOf(1) }
    var sheetTitle by remember { mutableStateOf("") }

    val localPath = spec.localPath
    val localPlayback = localPath.isNotBlank()
    val maxEp = seasons.firstOrNull { it.se == se }?.maxEp ?: 0

    fun setSource(item: StreamRes, startMs: Long) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(Api.WEB_UA)
            .setDefaultRequestProperties(mapOf("Referer" to item.referer))
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        player.setMediaSource(
            ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(item.url))
        )
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
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
        player.playWhenReady = true
        status = null
    }

    fun orderCandidates(list: List<StreamRes>): List<StreamRes> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val target = if (wifi) 1080 else 480
        val pool = list.filter { it.resolution <= target }.ifEmpty { list }
        val bestRes = pool.maxOf { it.resolution }
        val same = pool.filter { it.resolution == bestRes }
        val first = same.firstOrNull { isH264Codec(it.codec) } ?: same.first()
        val rest = list.filter { it !== first }.sortedWith(
            compareByDescending<StreamRes> { it.resolution }
                .thenBy { if (isH264Codec(it.codec)) 0 else 1 }
        )
        return listOf(first) + rest
    }

    // metadata
    LaunchedEffect(spec.baseId, localPlayback) {
        if (localPlayback || spec.baseId.isBlank()) return@LaunchedEffect
        runCatching { Api.detail(spec.baseId) }
            .onSuccess { d -> if (d.title.isNotBlank()) detail = d }
        runCatching { Api.dubs(spec.baseId) }
            .onSuccess { if (it.isNotEmpty()) dubs = it }
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
        val first = orderCandidates(list).first()
        status = "Opening ${first.resolution}p…"
        setSource(first, start)
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
                    if (spec.series && ep < maxEp) {
                        ep += 1
                        pendingSeek = 0L
                    }
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
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, locked) {
        if (controlsVisible && isPlaying && !locked) {
            delay(4000)
            controlsVisible = false
            menu = 0
        }
    }

    LaunchedEffect(fill) {
        playerView?.resizeMode =
            if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
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
        showAudioMenu = false
        scope.launch { runCatching { Api.detail(dub.id) }.getOrNull()?.let { d -> if (d.title.isNotBlank()) detail = d } }
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
            sheet = list
            sheetLoading = false
            if (list.isEmpty()) toast = "No downloadable stream for this title."
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

            Box(
                Modifier.fillMaxSize().pointerInput(locked) {
                    detectTapGestures {
                        if (locked) return@detectTapGestures
                        if (menu != 0) menu = 0 else controlsVisible = !controlsVisible
                    }
                }
            )

            if (buffering && status == null && error == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                )
            }
            status?.let {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.White, fontSize = 11.sp)
                }
            }

            if (locked) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x99000000))
                        .clickable { locked = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("🔒  Tap to Unlock", color = Color.White, fontSize = 11.sp) }
            }

            if (controlsVisible && !locked) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                        .padding(start = 2.dp, end = 8.dp, top = 2.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                        Text("‹", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.title.ifBlank { spec.title },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = buildList {
                            if (spec.series) add("S" + "%02d".format(se) + " E" + "%02d".format(ep))
                            if (localPlayback) add("downloaded")
                            else {
                                dubs.firstOrNull { it.id == activeDub }?.let { add(langLabel(it)) }
                                current?.let { add("${it.resolution}p ${codecLabel(it.codec)}") }
                            }
                        }.joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, color = Color(0xFFCDD4E0), fontSize = 10.sp, maxLines = 1)
                    }
                    Box(
                        Modifier.size(34.dp).clickable {
                            fullscreen = !fullscreen
                            controlsVisible = true
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (fullscreen) "⤡" else "⛶", color = Color.White, fontSize = 17.sp) }
                    Box(
                        Modifier.size(34.dp).clickable { menu = if (menu == 4) 0 else 4 },
                        contentAlignment = Alignment.Center,
                    ) { Text("⚙", color = Color.White, fontSize = 17.sp) }
                }

                // center
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    CircleCtl("−10") { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC000000))
                            .clickable {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPlaying) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(5.dp, 17.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                                Box(Modifier.size(5.dp, 17.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                            }
                        } else {
                            Text("▶", color = Color.White, fontSize = 22.sp)
                        }
                    }
                    CircleCtl("+10") { player.seekTo(player.currentPosition + 10_000) }
                }

                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x66000000))
                        .clickable {
                            locked = true
                            controlsVisible = false
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) { Text("🔓  Tap to Lock", color = Color.White, fontSize = 10.sp) }

                // bottom
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE6000000))))
                        .padding(start = 10.dp, end = 10.dp, top = 34.dp, bottom = 6.dp),
                ) {
                    val dur = durationMs.coerceAtLeast(1L)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fmtTime(if (seeking) seekValue.toLong() else positionMs),
                            color = Color.White,
                            fontSize = 9.sp,
                        )
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (isPlaying) "❚❚" else "▶",
                            color = Color.White,
                            fontSize = if (isPlaying) 12.sp else 15.sp,
                            modifier = Modifier.clickable {
                                if (player.isPlaying) player.pause() else player.play()
                                controlsVisible = true
                            }.padding(6.dp),
                        )
                        if (spec.series && ep < maxEp) {
                            Text(
                                "⏭",
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable {
                                    ep += 1
                                    pendingSeek = 0L
                                    controlsVisible = true
                                }.padding(6.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Mini(text = if (fill) "Fill" else "Fit") { fill = !fill }
                        if (dubs.size > 1) {
                            Mini(dubs.firstOrNull { it.id == activeDub }?.let { langLabel(it) } ?: "Language") {
                                menu = if (menu == 2) 0 else 2
                                controlsVisible = true
                            }
                        }
                        Mini(if (speed == 1f) "1x" else "${speed}x") { menu = if (menu == 3) 0 else 3 }
                        Mini(current?.let { "${it.resolution}P" } ?: "AUTO") { menu = if (menu == 1) 0 else 1 }
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
                            PanelLine(if (s == 1f) "1x (normal)" else "${s}x", "", speed == s) {
                                speed = s
                                player.setPlaybackSpeed(s)
                                menu = 0
                            }
                        }
                    }
                    else -> Unit
                }

                if (menu == 4) {
                    PanelOverlay("Settings", Alignment.TopEnd) {
                        PanelLine("Fullscreen", "", fullscreen) { fullscreen = !fullscreen; menu = 0 }
                        PanelLine("Lock screen", "", locked) { locked = true; menu = 0 }
                        PanelLine(if (fill) "Fit video" else "Fill screen", "", false) { fill = !fill; menu = 0 }
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
                    Text(msg, color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center)
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

        // ------------------------------------------------------------------ details (portrait)
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
                    Text("Info ›", color = TextDim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                val meta = buildList {
                    if (detail.rating.isNotBlank() && detail.rating != "0") add("★ ${detail.rating}")
                    if (detail.year.isNotBlank()) add(detail.year)
                    if (detail.country.isNotBlank()) add(detail.country)
                    if (detail.genre.isNotBlank()) add(detail.genre.substringBefore(","))
                    if (spec.series && seasons.isNotEmpty()) add("${seasons.size} seasons")
                }.joinToString("  |  ")
                if (meta.isNotBlank()) Text(meta, color = TextDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

                if (detail.desc.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        detail.desc,
                        color = Color(0xFFC7CFDD),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }

                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        ActionChip(if (inList) "✓ In list" else "＋ Add list") {
                            inList = !inList
                            ctx.getSharedPreferences("watchlist", Context.MODE_PRIVATE)
                                .edit().putBoolean(spec.baseId, inList).apply()
                        }
                    }
                    item {
                        ActionChip("↗ Share") {
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
                    }
                    item { ActionChip("⤓ Download") { loadQualities(if (spec.series) ep else 0) } }
                    item { ActionChip("⤓ Downloads") { onBack() } }
                }

                toast?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Accent, fontSize = 11.sp)
                }

                Spacer(Modifier.height(14.dp))
                Text("Resources", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // audio languages as a visible list, like the stock app's dropdown row
                if (dubs.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(dubs.size) { i ->
                            val d = dubs[i]
                            LangChip(langLabel(d), d.id == activeDub) {
                                switchAudio(d)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (spec.series && seasons.isNotEmpty()) {
                        DropDown("Season " + "%02d".format(se)) {
                            showSeasonMenu = !showSeasonMenu
                            showAudioMenu = false
                        }
                    }
                    DropDown(if (spec.series) "Season list" else "Movie") {
                        showSeasonMenu = !showSeasonMenu
                    }
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
                        ep = target
                        pendingSeek = 0L
                    }
                    Spacer(Modifier.height(8.dp))
                    val shown = if (ep <= 0) (1..maxEp).toList() else listOf(ep)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        shown.forEach { e ->
                            EpisodeLine(
                                ep = e,
                                active = e == ep,
                                onPlay = {
                                    ep = e
                                    pendingSeek = 0L
                                },
                                onDownload = { loadQualities(e) },
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Accent)
                            .clickable { retryKey++ }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▶  Play", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(7.dp))
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
                Spacer(Modifier.height(26.dp))
            }
        }
    }

    // ------------------------------------------------------------------ download sheet
    if (sheet.isNotEmpty() || sheetLoading) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { if (!sheetLoading) sheet = emptyList() }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF171B23))
                    .padding(16.dp),
            ) {
                Text("Download · $sheetTitle", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (sheetLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Fetching qualities…", color = TextDim, fontSize = 12.sp)
                    }
                }
                sheet.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
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
                        Text("${s.resolution}p", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(s.codec, color = TextDim, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(sizeText(s.size), color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("⤓", color = Accent, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- small pieces

@Composable
private fun CircleCtl(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 10.sp) }
}

@Composable
private fun Mini(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x66000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PanelOverlay(
    title: String,
    align: Alignment,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(10.dp),
        contentAlignment = align,
    ) {
        Column(
            Modifier
                .widthIn(min = 150.dp, max = 240.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xF2141821))
                .padding(vertical = 6.dp, horizontal = 10.dp),
        ) {
            Text(title, color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
            content()
        }
    }
}

@Composable
private fun PanelLine(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) Accent else Color.White,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (tag.isNotEmpty()) {
            Spacer(Modifier.width(5.dp))
            Text(tag, color = TextDim, fontSize = 9.sp)
        }
        Spacer(Modifier.weight(1f))
        if (selected) Text("✓", color = Accent, fontSize = 11.sp)
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = TextMain,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    )
}

@Composable
private fun LangChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color(0xFF2BD67B) else TextDim,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color(0xFF12352A) else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun DropDown(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMain, fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        Text("▾", color = TextDim, fontSize = 10.sp)
    }
}

@Composable
private fun MenuBlock(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Surface1)
            .padding(vertical = 3.dp),
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
        Text(text, color = if (selected) Accent else TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = Accent, fontSize = 11.sp)
    }
}

@Composable
private fun EpisodeChips(maxEp: Int, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        item { EpChip("All", selected <= 0) { onSelect(0) } }
        items(maxEp) { i ->
            val e = i + 1
            EpChip("%02d".format(e), selected == e) { onSelect(e) }
        }
    }
}

@Composable
private fun EpChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp, 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF12352A) else Surface1)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color(0xFF2BD67B) else TextDim,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EpisodeLine(ep: Int, active: Boolean, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Color(0xFF14251F) else Surface1)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "E" + "%02d".format(ep),
            color = if (active) Color(0xFF2BD67B) else TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onPlay),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (active) "Playing" else "Tap to play",
            color = TextDim,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f).clickable(onClick = onPlay),
        )
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Surface2)
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) { Text("⤓", color = Accent, fontSize = 15.sp) }
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = Color.White, fontSize = 11.sp) }
    }
}
