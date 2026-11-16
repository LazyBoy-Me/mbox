package app.mbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.ui.Accent
import app.mbox.ui.Bg
import app.mbox.ui.DetailScreen
import app.mbox.ui.HomeScreen
import app.mbox.ui.MBoxTheme
import app.mbox.ui.MessageBox
import app.mbox.ui.PlayerScreen
import app.mbox.ui.SearchScreen
import app.mbox.ui.Surface1
import app.mbox.ui.TextDim

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
    var detail by remember { mutableStateOf<Subject?>(null) }
    var playback by remember { mutableStateOf<PlaySpec?>(null) }

    LaunchedEffect(Unit) { Api.ensureToken() }

    BackHandler(enabled = playback != null) { playback = null }
    BackHandler(enabled = playback == null && detail != null) { detail = null }

    val currentPlayback = playback
    val currentDetail = detail

    // Movies start playing straight from the poster; series still need an episode picker.
    fun open(s: Subject) {
        if (s.isSeries) detail = s
        else playback = PlaySpec(baseId = s.id, title = s.title)
    }

    when {
        currentPlayback != null -> PlayerScreen(
            spec = currentPlayback,
            onBack = { playback = null },
        )

        currentDetail != null -> DetailScreen(
            initial = currentDetail,
            onBack = { detail = null },
            onPlay = { playback = it },
        )

        else -> Scaffold(
            containerColor = Bg,
            bottomBar = { BottomBar(tab) { tab = it } },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(Bg).padding(padding)) {
                when (tab) {
                    0 -> HomeScreen(onOpen = { open(it) }, onSearch = { tab = 1 })
                    1 -> SearchScreen(onOpen = { open(it) })
                    2 -> Placeholder("Downloads", "Downloads will show up here.")
                    else -> Placeholder("Me", "Signed in as guest.\nNo account needed for playback.")
                }
            }
        }
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
            icon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Downloads") },
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
    indicatorColor = Surface1,
    unselectedIconColor = TextDim,
    unselectedTextColor = TextDim,
)

@Composable
private fun Placeholder(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = title,
            color = androidx.compose.ui.graphics.Color(0xFFF2F4F8),
            fontSize = 22.sp,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp),
        )
        MessageBox(subtitle)
    }
}
