package app.mbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var status by remember { mutableStateOf<String?>("Preparing stream…") }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var resumeAt by remember { mutableStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var showQuality by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var rotateMode by remember { mutableStateOf(1) } // 0 auto, 1 landscape, 2 portrait

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }

    fun setSource(item: StreamRes, startMs: Long) {
        // The CDN only serves bytes to requests carrying the web player's Referer (and a browser
        // UA); without it the edge answers 429. Both are pinned on the data source.
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

    /** Picks the best candidate for the current network, then falls back down the list. */
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

    LaunchedEffect(spec.baseId) {
        val list = runCatching { Api.dubs(spec.baseId) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) dubs = list
    }

    LaunchedEffect(activeDub, retryKey) {
        val start = resumeAt
        resumeAt = 0L
        error = null
        status = "Preparing stream…"
        val list = runCatching { Api.h5Play(activeDub, spec.se, spec.ep) }.getOrNull()
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
        error = null
        status = "Switching to ${item.resolution}p…"
        setSource(item, pos)
    }

    fun switchAudio(dub: Dub) {
        if (dub.id == activeDub) return
        resumeAt = player.currentPosition.coerceAtLeast(0)
        activeDub = dub.id
        showAudio = false
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controlsVisible = true
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
        if (controlsVisible && isPlaying) {
            delay(3500)
            controlsVisible = false
        }
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
            showQuality -> showQuality = false
            showAudio -> showAudio = false
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
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // tap layer: toggles controls, closes any open panel
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (showQuality || showAudio) {
                            showQuality = false
                            showAudio = false
                        } else {
                            controlsVisible = !controlsVisible
                        }
                    }
                }
        )

        if (buffering && status == null && error == null) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }

        status?.let {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color.White, fontSize = 13.sp)
            }
        }

        if (controlsVisible) {
            // top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color(0x00000000))
                        )
                    )
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
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
                        if (spec.series) add("S${spec.se} E${spec.ep}")
                        dubs.firstOrNull { it.id == activeDub }?.let { add(langLabel(it)) }
                        current?.let { add("${it.resolution}p ${codecLabel(it.codec)}") }
                    }.joinToString("  ·  ")
                    if (sub.isNotEmpty()) {
                        Text(sub, color = Color(0xFFCDD4E0), fontSize = 11.sp, maxLines = 1)
                    }
                }
                Chip(
                    text = when (rotateMode) {
                        1 -> "Landscape"
                        2 -> "Portrait"
                        else -> "Auto"
                    },
                    onClick = { rotateMode = (rotateMode + 1) % 3 },
                )
                if (dubs.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    Chip(
                        text = dubs.firstOrNull { it.id == activeDub }?.let { langLabel(it) } ?: "Audio",
                        onClick = { showAudio = !showAudio; showQuality = false },
                    )
                }
                Spacer(Modifier.width(6.dp))
                Chip(
                    text = current?.let { "${it.resolution}p" } ?: "Auto",
                    onClick = { showQuality = !showQuality; showAudio = false },
                )
            }

            // bottom bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x00000000), Color(0xE6000000))
                        )
                    )
                    .padding(start = 12.dp, end = 12.dp, top = 40.dp, bottom = 14.dp),
            ) {
                val dur = durationMs.coerceAtLeast(1L)
                Slider(
                    value = if (seeking) seekValue else positionMs.toFloat().coerceIn(0f, dur.toFloat()),
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
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            controlsVisible = true
                        }
                    ) {
                        if (isPlaying) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Bar()
                                Bar()
                            }
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    TextButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) {
                        Text("−10s", color = Color.White, fontSize = 12.sp)
                    }
                    TextButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) {
                        Text("+10s", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    val shownPos = if (seeking) seekValue.toLong() else positionMs
                    Text(
                        "${fmtTime(shownPos)} / ${fmtTime(durationMs)}",
                        color = Color(0xFFCDD4E0),
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (showQuality) {
            Panel(
                title = "Quality",
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                items.forEach { item ->
                    PanelRow(
                        label = "${item.resolution}p",
                        tag = listOf(codecLabel(item.codec), fmtSize(item.size))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        selected = item === current,
                        onClick = {
                            showQuality = false
                            switchQuality(item)
                        },
                    )
                }
            }
        }

        if (showAudio) {
            Panel(
                title = "Audio",
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                dubs.forEach { dub ->
                    PanelRow(
                        label = langLabel(dub),
                        tag = if (dub.original) "original" else "",
                        selected = dub.id == activeDub,
                        onClick = { switchAudio(dub) },
                    )
                }
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
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Couldn't start playback",
                    color = Color.White,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = TextDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        error = null
                        status = "Preparing stream…"
                        retryKey++
                    }) {
                        Text("Retry", color = Accent, fontSize = 14.sp)
                    }
                    val alt = items.firstOrNull { it !== current }
                    if (alt != null) {
                        TextButton(onClick = {
                            error = null
                            switchQuality(alt)
                        }) {
                            Text("Try ${alt.resolution}p ${codecLabel(alt.codec)}", color = Color.White, fontSize = 14.sp)
                        }
                    }
                    TextButton(onClick = onBack) {
                        Text("Back", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar() {
    Box(
        Modifier
            .size(6.dp, 20.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White)
    )
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x66000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(text, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
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
            .widthIn(min = 150.dp, max = 240.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2141821))
            .padding(vertical = 8.dp, horizontal = 12.dp),
    ) {
        Text(title, color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun PanelRow(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        if (tag.isNotEmpty()) {
            Text(tag, color = TextDim, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        if (selected) Text("✓", color = Accent, fontSize = 13.sp)
    }
}
