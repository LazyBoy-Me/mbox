package app.mbox

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
        Store.init(applicationContext)
        setContent {
            MBoxTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    var tab by remember { mutableStateOf(0) }
    var playback by remember { mutableStateOf<PlaySpec?>(null) }

    LaunchedEffect(Unit) { Api.ensureToken() }

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
                    version = "1.3",
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
    Row(
        Modifier
            .background(androidx.compose.ui.graphics.Color(0xFF16181D))
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabItem("Home", R.drawable.ic_tab_home_active, R.drawable.ic_tab_home_unactive_dark, selected == 0) { onSelect(0) }
        TabItem("Search", R.drawable.ic_search, R.drawable.ic_search, selected == 1, plain = true) { onSelect(1) }
        TabItem("Downloads", R.drawable.ic_tab_downloads_active, R.drawable.ic_tab_downloads_unactive_dark, selected == 2) { onSelect(2) }
        TabItem("Me", R.drawable.ic_tab_me_active, R.drawable.ic_tab_me_unactive_dark, selected == 3) { onSelect(3) }
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
            .width(72.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (plain) {
            androidx.compose.material3.Icon(
                painter = painterResource(inactiveIcon),
                contentDescription = label,
                tint = if (active) androidx.compose.ui.graphics.Color(0xFF2FF58B) else TextMute,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Image(
                painter = painterResource(if (active) activeIcon else inactiveIcon),
                contentDescription = label,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = if (active) androidx.compose.ui.graphics.Color(0xFF2FF58B) else TextMute,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
