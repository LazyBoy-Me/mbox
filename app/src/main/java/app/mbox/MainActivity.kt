package app.mbox

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.ui.Bg
import app.mbox.ui.DownloadsScreen
import app.mbox.ui.HomeScreen
import app.mbox.ui.MBoxTheme
import app.mbox.ui.MeScreen
import app.mbox.ui.SearchScreen
import app.mbox.ui.TextMain
import app.mbox.ui.TextMute
import app.mbox.ui.WatchScreen

class MainActivity : ComponentActivity() {

    private val inPip = mutableStateOf(false)
    private val deepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink.value = subjectIdFrom(intent)
        setContent {
            MBoxTheme {
                App(
                    inPip = inPip.value,
                    deepLink = deepLink.value,
                    onLinkUsed = { deepLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        subjectIdFrom(intent)?.let { deepLink.value = it }
    }

    /**
     * API 26–30 have no auto-enter flag, so Home has to be caught here. From API 31 the player
     * enables auto-enter itself through [Pip.params].
     */
    override fun onUserLeaveHint() {
        if (Pip.active && !inPip.value &&
            Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R
        ) {
            runCatching { enterPictureInPictureMode(Pip.params(this, autoEnter = false)) }
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /** mbox://subject/<id> — the link the player's Share button produces. */
    private fun subjectIdFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (!data.scheme.equals("mbox", true) || !data.host.equals("subject", true)) return null
        return data.lastPathSegment?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun App(inPip: Boolean, deepLink: String?, onLinkUsed: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var playback by remember { mutableStateOf<PlaySpec?>(null) }

    LaunchedEffect(Unit) { Api.ensureToken() }

    // A shared link opens the title straight away; the player fetches the rest of the metadata.
    LaunchedEffect(deepLink) {
        val id = deepLink ?: return@LaunchedEffect
        val d = runCatching { Api.detail(id) }.getOrNull()
        val series = d?.isSeries == true
        playback = PlaySpec(
            baseId = id,
            title = d?.title.orEmpty(),
            series = series,
            se = if (series) 1 else 0,
            ep = if (series) 1 else 0,
        )
        onLinkUsed()
    }

    BackHandler(enabled = playback != null) { playback = null }

    val currentPlayback = playback

    // Every tap opens the one watch screen: video on top, then audio/season/episode lists.
    // Movies are addressed as se=0&ep=0, series start at season 1 episode 1.
    fun open(s: Subject) {
        playback = PlaySpec(
            baseId = s.id,
            title = s.title,
            series = s.isSeries,
            se = if (s.isSeries) 1 else 0,
            ep = if (s.isSeries) 1 else 0,
        )
    }

    if (currentPlayback != null) {
        key(currentPlayback.baseId, currentPlayback.localPath) {
            WatchScreen(
                spec = currentPlayback,
                inPip = inPip,
                onBack = { playback = null },
                onOpen = { open(it) },
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeScreen(
                    onOpen = { open(it) },
                    onSearch = { tab = 1 },
                    onDownloads = { tab = 2 },
                )

                1 -> SearchScreen(
                    onOpen = { open(it) },
                    onBack = { tab = 0 },
                )

                2 -> DownloadsScreen(
                    onOpen = { playback = it },
                )

                else -> MeScreen(
                    version = "1.6",
                    onDownloads = { tab = 2 },
                    onOpen = { open(it) },
                )
            }
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(androidx.compose.ui.graphics.Color(0x1FFFFFFF)))
        Row(
            Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF16181D))
                .height(58.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TabItem("Home", R.drawable.ic_tab_home_active, R.drawable.ic_tab_home_unactive_dark, selected == 0) { onSelect(0) }
            TabItem("Search", R.drawable.ic_tab_search, R.drawable.ic_tab_search, selected == 1, plain = true) { onSelect(1) }
            TabItem("Downloads", R.drawable.ic_tab_downloads_active, R.drawable.ic_tab_downloads_unactive_dark, selected == 2) { onSelect(2) }
            TabItem("Me", R.drawable.ic_tab_me_active, R.drawable.ic_tab_me_unactive_dark, selected == 3) { onSelect(3) }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    activeIcon: Int,
    inactiveIcon: Int,
    active: Boolean,
    plain: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(74.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (plain) {
            androidx.compose.material3.Icon(
                painter = painterResource(inactiveIcon),
                contentDescription = label,
                tint = if (active) androidx.compose.ui.graphics.Color(0xFF2FF58B)
                else androidx.compose.ui.graphics.Color(0xE6FFFFFF),
                modifier = Modifier.size(24.dp),
            )
        } else {
            Image(
                painter = painterResource(if (active) activeIcon else inactiveIcon),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = if (active) androidx.compose.ui.graphics.Color(0xFF2FF58B)
            else androidx.compose.ui.graphics.Color(0x99FFFFFF),
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
