package app.mbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.HistoryEntry
import app.mbox.R
import app.mbox.Store
import app.mbox.Subject
import coil.compose.AsyncImage
import androidx.compose.foundation.Image

@Composable
fun MeScreen(
    version: String,
    onDownloads: () -> Unit,
    onOpen: (Subject) -> Unit,
    onResume: (HistoryEntry) -> Unit,
) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(Store.history()) }
    var myList by remember { mutableStateOf(Store.myList()) }

    when (page) {
        1 -> HistoryPage(
            items = history,
            onBack = { page = 0; history = Store.history() },
            onOpen = { h -> onResume(h) },
            onRemove = { id ->
                Store.removeHistory(id)
                history = Store.history()
            },
            onClear = {
                Store.clearHistory()
                history = emptyList()
            },
        )

        2 -> ListPage(
            items = myList,
            onBack = { page = 0; myList = Store.myList() },
            onOpen = onOpen,
        )

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(Surface2),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.ic_default_avatar),
                            contentDescription = null,
                            modifier = Modifier.size(54.dp).clip(CircleShape),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Guest", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("No account needed · full quality", color = TextMute, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        painter = painterResource(R.drawable.ic_me_setting),
                        contentDescription = null,
                        tint = TextMute,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard("History", "${history.size}", Modifier.weight(1f)) { page = 1 }
                    StatCard("My List", "${myList.size}", Modifier.weight(1f)) { page = 2 }
                    StatCard("Downloads", "", Modifier.weight(1f)) { onDownloads() }
                }
            }

            item { MeGroupTitle("Library") }
            item {
                MeRow(R.drawable.ic_me_history, "Watch history", "Everything you started") { page = 1 }
            }
            item { MeRow(R.drawable.ic_me_my_list, "My list", "Saved titles") { page = 2 } }
            item { MeRow(R.drawable.ic_movie_download, "Downloads", "Offline copies") { onDownloads() } }

            item { MeGroupTitle("Playback") }
            item {
                var autoplay by remember { mutableStateOf(Store.autoplayNext) }
                SwitchRow("Auto play next episode", autoplay) {
                    autoplay = !autoplay
                    Store.autoplayNext = autoplay
                }
            }
            item {
                var saver by remember { mutableStateOf(Store.dataSaver) }
                SwitchRow("Data saver (480p on mobile)", saver) {
                    saver = !saver
                    Store.dataSaver = saver
                }
            }
            item {
                var wifi by remember { mutableStateOf(Store.wifiOnlyDownloads) }
                SwitchRow("Download over Wi-Fi only", wifi) {
                    wifi = !wifi
                    Store.wifiOnlyDownloads = wifi
                }
            }
            item {
                val langs = listOf("English", "বাংলা", "हिन्दी", "Off")
                var lang by remember { mutableStateOf(Store.subtitleLang) }
                ValueRow("Subtitle language", lang) {
                    val i = langs.indexOf(lang).coerceAtLeast(0)
                    lang = langs[(i + 1) % langs.size]
                    Store.subtitleLang = lang
                }
            }
            item {
                val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
                var speed by remember { mutableStateOf(Store.speed) }
                ValueRow("Default speed", if (speed == 1f) "1x" else "${speed}x") {
                    val i = speeds.indexOf(speed).coerceAtLeast(0)
                    speed = speeds[(i + 1) % speeds.size]
                    Store.speed = speed
                }
            }

            item { MeGroupTitle("Storage") }
            item {
                MeRowValue(
                    R.drawable.ic_movie_download,
                    "Downloaded files",
                    sizeText(app.mbox.Downloads.folderSize(ctx)),
                ) { onDownloads() }
            }

            item { MeGroupTitle("About") }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("MBox $version", color = TextDim, fontSize = 13.sp)
                    val crash = remember { Store.lastCrash }
                    if (crash.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Last crash: " + crash.lineSequence().first(),
                            color = Color(0xFFFF8A80),
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Playback: web BFF streams (referer-pinned), no account, no VIP.",
                        color = TextMute,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Icon set and palette taken from the stock app's resources.",
                        color = TextMute,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1_000_000_000.0)
    else -> String.format("%.0f MB", bytes / 1_000_000.0)
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
    ) {
        Text(label, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (value.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(value, color = TextMute, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MeGroupTitle(text: String) {
    Text(
        text,
        color = TextMute,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun MeRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = TextDim,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 14.sp)
            Text(subtitle, color = TextMute, fontSize = 10.sp)
        }
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right_white_small),
            contentDescription = null,
            tint = TextMute,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun MeRowValue(icon: Int, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = TextDim,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMute, fontSize = 12.sp)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Accent,
                uncheckedTrackColor = Surface2,
                uncheckedThumbColor = TextMute,
            ),
        )
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = AccentSoft, fontSize = 13.sp)
    }
}

// ------------------------------------------------------------------ history

@Composable
private fun HistoryPage(
    items: List<HistoryEntry>,
    onBack: () -> Unit,
    onOpen: (HistoryEntry) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageBar("Watch history", onBack) {
            Text("Clear", color = AccentSoft, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClear))
        }
        if (items.isEmpty()) {
            MessageBox("Nothing watched yet.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items.size) { i ->
                val h = items[i]
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(h) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(96.dp, 56.dp).clip(RoundedCornerShape(4.dp)).background(Surface2),
                    ) {
                        AsyncImage(
                            model = h.still.ifBlank { h.cover },
                            contentDescription = h.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.fillMaxWidth().height(2.5.dp).align(Alignment.BottomCenter)
                                .background(Color(0x66FFFFFF))
                        )
                        Box(
                            Modifier.fillMaxWidth(h.progress).height(2.5.dp).align(Alignment.BottomCenter)
                                .background(Accent)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                if (h.type == 2) append("S${h.se} E${h.ep} · ")
                                append("${(h.progress * 100).toInt()}% watched")
                            },
                            color = TextMute,
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        "✕",
                        color = TextMute,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onRemove(h.id) }.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ListPage(items: List<Subject>, onBack: () -> Unit, onOpen: (Subject) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageBar("My list", onBack) {}
        if (items.isEmpty()) {
            MessageBox("Titles you like are saved here.")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items.size) { i ->
                PosterCard(items[i], Modifier.fillMaxWidth()) { onOpen(items[i]) }
            }
        }
    }
}

@Composable
private fun PageBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back_black_24),
                contentDescription = "Back",
                tint = TextMain,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}
