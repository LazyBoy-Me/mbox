package app.mbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import app.mbox.Dub
import app.mbox.PlaySpec
import app.mbox.StreamRes
import kotlinx.coroutines.delay
import java.io.File

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return c as? Activity
}

private fun isH264(codec: String): Boolean {
    val c = codec.lowercase()
    return c == "h264" || c == "avc" || c == "avc1"
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(spec: PlaySpec, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val player = remember { ExoPlayer.Builder(ctx).build() }

    var dubs by remember { mutableStateOf<List<Dub>>(emptyList()) }
    var activeDub by remember { mutableStateOf(spec.dubId.ifBlank { spec.baseId }) }
    var items by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var current by remember { mutableStateOf<StreamRes?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    var se by remember { mutableStateOf(spec.se) }
    var ep by remember { mutableStateOf(spec.ep) }
    var maxEp by remember { mutableStateOf(spec.maxEp) }
    var pendingSeek by remember { mutableStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(0) } // 0 none, 1 quality, 2 audio, 3 speed, 4 rotate
    var rotateMode by remember { mutableStateOf(1) } // 0 auto, 1 landscape, 2 portrait
    var speed by remember { mutableStateOf(1f) }
    var fill by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }

    val localPlayback = spec.localPath.isNotBlank()

    fun setSource(item: StreamRes, startMs: Long) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(Api.WEB_UA)
            .setDefaultRequestProperties(mapOf("Referer" to item.referer))
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val media = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(item.url))
        player.setMediaSource(media)
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
        player.playWhenReady = true
        current = item
        error = null
        status = null
    }

    fun setLocal(path: String) {
        // DefaultDataSource resolves file:// inside the app folder, no referer involved.
        val base = DefaultHttpDataSource.Factory().setUserAgent(Api.PLAYER_UA)
        val src = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx, base))
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.setMediaSource(src)
        player.prepare()
        player.playWhenReady = true
        status = null
    }

    /** Wi-Fi gets 1080, mobile data 480 — same policy the stock app uses. */
    fun orderCandidates(list: List<StreamRes>): List<StreamRes> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val target = if (wifi) 1080 else 480
        val pool = list.filter { it.resolution <= target }.ifEmpty { list }
        val bestRes = pool.maxOf { it.resolution }
        val same = pool.filter { it.resolution == bestRes }
        val first = same.firstOrNull { isH264(it.codec) } ?: same.first()
        val rest = list.filter { it !== first }.sortedWith(
            compareByDescending<StreamRes> { it.resolution }
                .thenBy { if (isH264(it.codec)) 0 else 1 }
        )
        return listOf(first) + rest
    }

    LaunchedEffect(spec.baseId, localPlayback) {
        if (localPlayback) return@LaunchedEffect
        val list = runCatching { Api.dubs(spec.baseId) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) dubs = list
    }

    LaunchedEffect(activeDub, retryKey, localPlayback, spec.localPath, se, ep) {
        if (localPlayback) {
            status = "Opening download…"
            setLocal(spec.localPath)
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
        items = list
        if (list.isEmpty()) {
            status = null
            error = "No stream available for this title."
            return@LaunchedEffect
        }
        val first = orderCandidates(list).first()
        status = "Opening ${first.resolution}p…"
        setSource(first, start)
    }

    fun switchQuality(item: StreamRes) {
        if (item === current) return
        if (item.url.isEmpty()) return
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
    }

    fun nextEpisode() {
        if (!spec.series || ep >= maxEp) return
        ep += 1
        pendingSeek = 0L
        menu = 0
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
                    nextEpisode()
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

    LaunchedEffect(controlsVisible, isPlaying) {
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

    LaunchedEffect(rotateMode) {
        activity?.requestedOrientation = when (rotateMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
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
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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

        // tap layer
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
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
                modifier = Modifier.align(Alignment.Center).size(38.dp),
            )
        }

        status?.let {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(10.dp))
                Text(it, color = Color.White, fontSize = 12.sp)
            }
        }

        // ---- locked mode ----
        if (locked) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(18.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x99000000))
                    .clickable { locked = false }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text("🔒  Tap to Unlock", color = Color.White, fontSize = 12.sp)
            }
        }

        if (!controlsVisible || locked) return@Box

        // ---- top bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonBox { onBack() }
            Column(Modifier.weight(1f)) {
                Text(
                    text = spec.title,
                    color = Color.White,
                    fontSize = 14.sp,
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
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x66000000))
                    .clickable { menu = if (menu == 4) 0 else 4 }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text("⚙  Settings", color = Color.White, fontSize = 11.sp)
            }
        }

        // ---- center controls ----
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            CircleButton(text = "−10") { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
            Box(
                Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .clickable { if (player.isPlaying) player.pause() else player.play(); controlsVisible = true },
                contentAlignment = Alignment.Center,
            ) {
                if (isPlaying) PauseGlyph() else Text("▶", color = Color.White, fontSize = 26.sp)
            }
            CircleButton(text = "+10") { player.seekTo(player.currentPosition + 10_000) }
        }

        // ---- tap to lock ----
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0x66000000))
                .clickable { locked = true; controlsVisible = false }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("🔓  Tap to Lock", color = Color.White, fontSize = 11.sp)
        }

        // ---- bottom bar ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE6000000))))
                .padding(start = 14.dp, end = 14.dp, top = 46.dp, bottom = 12.dp),
        ) {
            val dur = durationMs.coerceAtLeast(1L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(if (seeking) seekValue.toLong() else positionMs), color = Color.White, fontSize = 10.sp)
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
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(fmtTime(durationMs), color = Color.White, fontSize = 10.sp)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isPlaying) "❚❚" else "▶",
                        color = Color.White,
                        fontSize = if (isPlaying) 13.sp else 16.sp,
                        modifier = Modifier.clickable {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                        },
                    )
                }
                if (spec.series && ep < maxEp) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "⏭",
                            color = Color.White,
                            fontSize = 15.sp,
                            modifier = Modifier.clickable { nextEpisode() },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                MiniChip(if (fill) "Fill" else "Fit") { fill = !fill }
                if (dubs.size > 1) {
                    MiniChip(dubs.firstOrNull { it.id == activeDub }?.let { langLabel(it) } ?: "Language") {
                        menu = if (menu == 2) 0 else 2
                    }
                }
                MiniChip(if (speed == 1f) "1x" else "${speed}x") { menu = if (menu == 3) 0 else 3 }
                MiniChip(current?.let { "${it.resolution}P" } ?: "AUTO") { menu = if (menu == 1) 0 else 1 }
            }
        }

        // ---- panels ----
        when (menu) {
            1 -> Panel("Quality", Modifier.align(Alignment.BottomEnd)) {
                items.forEach { item ->
                    PanelRow(
                        label = "${item.resolution}p",
                        tag = listOf(codecLabel(item.codec), fmtSize(item.size)).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        selected = item === current,
                        onClick = { switchQuality(item) },
                    )
                }
            }
            2 -> Panel("Audio", Modifier.align(Alignment.BottomEnd)) {
                dubs.forEach { dub ->
                    PanelRow(
                        label = langLabel(dub),
                        tag = if (dub.original) "original" else "",
                        selected = dub.id == activeDub,
                        onClick = { switchAudio(dub) },
                    )
                }
            }
            3 -> Panel("Speed", Modifier.align(Alignment.BottomEnd)) {
                SPEEDS.forEach { s ->
                    PanelRow(
                        label = if (s == 1f) "1x (normal)" else "${s}x",
                        tag = "",
                        selected = speed == s,
                        onClick = {
                            speed = s
                            player.setPlaybackSpeed(s)
                            menu = 0
                        },
                    )
                }
            }
            4 -> Panel("Settings", Modifier.align(Alignment.TopEnd).padding(top = 64.dp)) {
                PanelRow("Landscape", "", rotateMode == 1) { rotateMode = 1; menu = 0 }
                PanelRow("Portrait", "", rotateMode == 2) { rotateMode = 2; menu = 0 }
                PanelRow("Auto rotate", "", rotateMode == 0) { rotateMode = 0; menu = 0 }
                PanelRow(if (fill) "Fit video" else "Fill screen", "", false) { fill = !fill; menu = 0 }
                PanelRow("Reload stream", "", false) { retryKey++; menu = 0 }
            }
        }

        error?.let { msg ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = spec.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(msg, color = TextDim, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MenuText("Retry") {
                        error = null
                        status = "Preparing stream…"
                        retryKey++
                    }
                    items.firstOrNull { it !== current }?.let { alt ->
                        MenuText("Try ${alt.resolution}p") {
                            error = null
                            switchQuality(alt)
                        }
                    }
                    MenuText("Back") { onBack() }
                }
            }
        }
    }
}

@Composable
private fun IconButtonBox(onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
    }
}

@Composable
private fun CircleButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun PauseGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp, 20.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        Box(Modifier.size(6.dp, 20.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
    }
}

@Composable
private fun MiniChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x66000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

@Composable
private fun MenuText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(end = 12.dp, bottom = 92.dp)
            .widthIn(min = 160.dp, max = 250.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2141821))
            .padding(vertical = 8.dp, horizontal = 12.dp),
    ) {
        Text(title, color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun PanelRow(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) Accent else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.width(6.dp))
        if (tag.isNotEmpty()) Text(tag, color = TextDim, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        if (selected) Text("✓", color = Accent, fontSize = 13.sp)
    }
}
