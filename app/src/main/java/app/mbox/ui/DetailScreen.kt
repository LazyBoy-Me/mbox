package app.mbox.ui

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.mbox.Api
import app.mbox.Downloads
import app.mbox.Dub
import app.mbox.PlaySpec
import app.mbox.Season
import app.mbox.StreamRes
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun DetailScreen(
    initial: Subject,
    onBack: () -> Unit,
    onPlay: (PlaySpec) -> Unit,
    onDownloads: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf(initial) }
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var dubs by remember { mutableStateOf<List<Dub>>(emptyList()) }
    var activeDub by remember { mutableStateOf(initial.id) }
    var selSe by remember { mutableStateOf(1) }
    var selEp by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var inList by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<List<StreamRes>>(emptyList()) }
    var sheetEp by remember { mutableStateOf(1) }
    var sheetTitle by remember { mutableStateOf("") }
    var sheetLoading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showSeasonMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }

    val prefs = remember { ctx.getSharedPreferences("watchlist", android.content.Context.MODE_PRIVATE) }

    suspend fun load(id: String) {
        runCatching { Api.detail(id) }.onSuccess { if (it.title.isNotBlank()) detail = it }
        if (detail.isSeries) {
            runCatching { Api.seasons(id) }.onSuccess { list ->
                seasons = list
                if (list.none { it.se == selSe }) selSe = list.firstOrNull()?.se ?: 1
            }
        } else {
            seasons = emptyList()
        }
    }

    LaunchedEffect(initial.id) {
        inList = prefs.getBoolean(initial.id, false)
        runCatching { Api.dubs(initial.id) }.onSuccess { if (it.isNotEmpty()) dubs = it }
        load(initial.id)
    }

    val maxEp = seasons.firstOrNull { it.se == selSe }?.maxEp ?: 0

    fun play(ep: Int) {
        onPlay(
            PlaySpec(
                baseId = initial.id,
                title = detail.title.ifBlank { initial.title },
                series = detail.isSeries,
                se = if (detail.isSeries) selSe else 0,
                ep = if (detail.isSeries) ep else 0,
                dubId = activeDub,
                maxEp = if (detail.isSeries) maxEp else 0,
            )
        )
    }

    fun loadQualities(ep: Int) {
        sheetEp = if (ep <= 0) 1 else ep
        sheetTitle = if (detail.isSeries) "S$selSe E$sheetEp" else "Movie"
        sheetLoading = true
        sheet = emptyList()
        scope.launch {
            val list = runCatching {
                Api.h5Play(activeDub, if (detail.isSeries) selSe else 0, if (detail.isSeries) sheetEp else 0)
            }.getOrDefault(emptyList())
            sheet = list
            sheetLoading = false
            if (list.isEmpty()) toast = "No downloadable stream for this title."
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Bg),
    ) {
        // cover preview + play overlay
        Box(Modifier.fillMaxWidth().height(250.dp).clickable { play(if (detail.isSeries) selEp.coerceAtLeast(1) else 0) }) {
            AsyncImage(
                model = detail.cover,
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0x33000000), Bg.copy(alpha = 0.55f), Bg))
                )
            )
            CircleBack(onBack = onBack, modifier = Modifier.padding(14.dp))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) { Text("▶", color = Accent, fontSize = 26.sp) }
            Text(
                text = if (detail.isSeries) "Tap to play S$selSe E1" else "Tap to play",
                color = Color(0xFFCBD3E1),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 10.dp),
            )
        }

        Column(Modifier.padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    detail.title,
                    color = TextMain,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("Info ›", color = TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            val meta = buildList {
                if (detail.rating.isNotBlank() && detail.rating != "0") add("★ ${detail.rating}")
                if (detail.year.isNotBlank()) add(detail.year)
                if (detail.country.isNotBlank()) add(detail.country)
                if (detail.genre.isNotBlank()) add(detail.genre.substringBefore(","))
                if (detail.isSeries && seasons.isNotEmpty()) add("${seasons.size} seasons")
            }.joinToString("  |  ")
            Text(meta, color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

            if (detail.desc.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    detail.desc,
                    color = Color(0xFFC7CFDD),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }

            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ActionChip(if (inList) "✓ In list" else "＋ Add list") {
                        inList = !inList
                        prefs.edit().putBoolean(initial.id, inList).apply()
                    }
                }
                item {
                    ActionChip("↗ Share") {
                        scope.launch {
                            val slug = runCatching { Api.detailPath(initial.id) }.getOrNull().orEmpty()
                            val link = if (slug.isEmpty()) detail.title else "https://movie-box.co/movies/$slug"
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "${detail.title}\n$link")
                            }
                            runCatching { ctx.startActivity(Intent.createChooser(send, "Share")) }
                        }
                    }
                }
                item {
                    ActionChip("⤓ Download") { loadQualities(if (detail.isSeries) selEp.coerceAtLeast(1) else 0) }
                }
                item { ActionChip("⤓ Downloads", onDownloads) }
            }

            toast?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Accent, fontSize = 12.sp)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Resources",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(
                    label = dubs.firstOrNull { it.id == activeDub }?.let { langLabel(it) } ?: "Original Audio",
                    onClick = { showAudioMenu = !showAudioMenu; showSeasonMenu = false },
                )
                if (detail.isSeries && seasons.isNotEmpty()) {
                    Dropdown(label = "Season ${"%02d".format(selSe)}") {
                        showSeasonMenu = !showSeasonMenu
                        showAudioMenu = false
                    }
                }
            }
            if (showAudioMenu && dubs.isNotEmpty()) {
                MenuCard {
                    dubs.forEach { d ->
                        MenuRow(
                            text = langLabel(d) + if (d.original) "  (original)" else "",
                            selected = d.id == activeDub,
                        ) {
                            activeDub = d.id
                            showAudioMenu = false
                            scope.launch {
                                if (detail.isSeries) load(activeDub)
                                else runCatching { Api.detail(activeDub) }
                            }
                        }
                    }
                }
            }
            if (showSeasonMenu && seasons.isNotEmpty()) {
                MenuCard {
                    seasons.forEach { s ->
                        MenuRow(text = "Season ${"%02d".format(s.se)}", selected = s.se == selSe) {
                            selSe = s.se
                            selEp = 0
                            showSeasonMenu = false
                        }
                    }
                }
            }

            if (detail.isSeries) {
                Spacer(Modifier.height(12.dp))
                EpisodeChips(
                    maxEp = maxEp,
                    selected = selEp,
                    onSelect = { selEp = it },
                )
                Spacer(Modifier.height(10.dp))
                val list = if (selEp == 0) (1..maxEp).toList() else listOf(selEp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { ep ->
                        EpisodeRow(
                            ep = ep,
                            onPlay = { play(ep) },
                            onDownload = { loadQualities(ep) },
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text("Tap the cover to play · or pick a download quality", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Accent)
                        .clickable { play(0) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶  Play now", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(26.dp))
        }
    }

    if (sheet.isNotEmpty() || sheetLoading) {
        Dialog(onDismissRequest = { if (!sheetLoading) sheet = emptyList() }) {
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
                                    key = "${initial.id}-${if (detail.isSeries) selSe else 0}-" +
                                        "${if (detail.isSeries) sheetEp else 0}-${s.resolution}-$activeDub",
                                    title = detail.title.ifBlank { initial.title },
                                    subtitle = if (detail.isSeries) "S${selSe}E$sheetEp ${s.resolution}p"
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
                        Text(fmtBytes(s.size), color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("⤓", color = Accent, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = TextMain,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun Dropdown(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMain, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text("▾", color = TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface1)
            .padding(vertical = 4.dp),
        content = { content() },
    )
}

@Composable
private fun MenuRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (selected) Accent else TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = Accent, fontSize = 12.sp)
    }
}

@Composable
private fun EpisodeChips(maxEp: Int, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
        item {
            ChipBox("All", selected == 0) { onSelect(0) }
        }
        items(maxEp) { i ->
            val ep = i + 1
            ChipBox("%02d".format(ep), selected == ep) { onSelect(ep) }
        }
    }
}

@Composable
private fun ChipBox(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp, 38.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color(0xFF12352A) else Surface1)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color(0xFF2BD67B) else TextDim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EpisodeRow(ep: Int, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface1)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "E" + "%02d".format(ep),
            color = TextMain,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onPlay),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Tap to play",
            color = TextDim,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f).clickable(onClick = onPlay),
        )
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2)
                .clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) { Text("⤓", color = Accent, fontSize = 16.sp) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = Color.White, fontSize = 12.sp) }
    }
}

private fun fmtBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}
