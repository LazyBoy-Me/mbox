package app.mbox.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import app.mbox.Api

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(url: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    LaunchedEffect(url, retryKey) {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(Api.PLAYER_UA)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val mediaSource = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(url))
        error = null
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.message ?: e.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        error?.let { msg ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    text = "Could not play this stream",
                    color = Color.White,
                    fontSize = 17.sp,
                )
                Text(
                    text = msg,
                    color = TextDim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = {
                    error = null
                    retryKey += 1
                }) {
                    Text("Retry", color = Accent, fontSize = 15.sp)
                }
                Text(
                    text = "Tip: go back and pick another quality (480p is H.264 and plays everywhere).",
                    color = TextDim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(top = 10.dp),
                )
            }
        }
    }
}
