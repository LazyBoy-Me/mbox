package app.mbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.ui.Accent
import app.mbox.ui.Bg
import app.mbox.ui.DownloadsScreen
import app.mbox.ui.HomeScreen
import app.mbox.ui.MBoxTheme
import app.mbox.ui.SearchScreen
import app.mbox.ui.WatchScreen
import app.mbox.ui.Surface1
import app.mbox.ui.Surface2
import app.mbox.ui.TextDim
import app.mbox.ui.TextMain

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
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

    when {
        currentPlayback != null -> key(currentPlayback.baseId, currentPlayback.localPath) {
            WatchScreen(
                spec = currentPlayback,
                onBack = { playback = null },
                onOpen = { open(it) },
            )
        }

        else -> Scaffold(
            containerColor = Bg,
            bottomBar = { BottomBar(tab) { tab = it } },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(Bg).padding(padding)) {
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
                        version = "1.2",
                        onDownloads = { tab = 2 },
                    )
                }
            }
        }
    }
}

@Composable
private fun MeScreen(version: String, onDownloads: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Me", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface1)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Surface2)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = TextDim)
            }
            Spacer(Modifier.padding(horizontal = 8.dp))
            Column {
                Text("Guest", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("No account needed · full quality", color = TextDim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface1)
                .clickable(onClick = onDownloads)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⤓", color = Accent, fontSize = 18.sp)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text("Downloads", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("›", color = TextDim, fontSize = 16.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("MBox $version · playback via web BFF", color = TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Audio: original + all available dubs. Quality: auto by network, changeable in the player.",
            color = TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Surface1) {
        NavigationBarItem(
            selected = selected == 0,
            onClick = { onSelect(0) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            label = { Text("Home", fontSize = 11.sp) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = selected == 1,
            onClick = { onSelect(1) },
            icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            label = { Text("Search", fontSize = 11.sp) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = selected == 2,
            onClick = { onSelect(2) },
            icon = { Text("⤓", fontSize = 18.sp, color = if (selected == 2) Accent else TextDim) },
            label = { Text("Downloads", fontSize = 11.sp) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = selected == 3,
            onClick = { onSelect(3) },
            icon = { Icon(Icons.Filled.Person, contentDescription = "Me") },
            label = { Text("Me", fontSize = 11.sp) },
            colors = navColors(),
        )
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Accent,
    selectedTextColor = Accent,
    indicatorColor = Color(0x22E50914),
    unselectedIconColor = TextDim,
    unselectedTextColor = TextDim,
)
