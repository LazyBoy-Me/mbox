package app.mbox.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.mbox.Api
import app.mbox.Dub
import app.mbox.PlaySpec
import app.mbox.Season
import app.mbox.Subject
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(initial: Subject, onBack: () -> Unit, onPlay: (PlaySpec) -> Unit) {
    var detail by remember { mutableStateOf(initial) }
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var dubs by remember { mutableStateOf<List<Dub>>(emptyList()) }
    var activeId by remember { mutableStateOf(initial.id) }
    var selSe by remember { mutableStateOf(1) }
    var selEp by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load(id: String) {
        runCatching { Api.detail(id) }.onSuccess { if (it.title.isNotBlank()) detail = it }
        if (detail.isSeries) {
            runCatching { Api.seasons(id) }.onSuccess { list ->
                seasons = list
                if (list.none { it.se == selSe }) selSe = list.firstOrNull()?.se ?: 1
                if (selEp < 1) selEp = 1
            }
        } else {
            seasons = emptyList()
        }
    }

    LaunchedEffect(initial.id) {
        runCatching { Api.dubs(initial.id) }.onSuccess { dubs = it }
        load(initial.id)
    }

    fun play(se: Int, ep: Int) {
        if (busy) return
        onPlay(
            PlaySpec(
                baseId = initial.id,
                title = detail.title.ifBlank { initial.title },
                series = detail.isSeries,
                se = se,
                ep = ep,
                dubId = activeId,
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // backdrop
        Box(Modifier.fillMaxWidth().height(275.dp)) {
            AsyncImage(
                model = detail.cover,
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x33000000),
                            0.6f to Bg.copy(alpha = 0.5f),
                            1f to Bg,
                        )
                    ),
            )
            CircleBack(onBack = onBack, modifier = Modifier.padding(14.dp))
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = detail.title,
                color = TextMain,
                fontSize = 23.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOf(detail.year, detail.duration, detail.genre)
                    .filter { it.isNotBlank() }
                    .joinToString("  ·  "),
                color = TextDim,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (detail.rating.isNotBlank() && detail.rating != "0") {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("★", color = Color(0xFFFFC107), fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(detail.rating, color = Color(0xFFFFC107), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("  IMDb", color = TextDim, fontSize = 12.sp)
                }
            }

            if (detail.desc.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = detail.desc,
                    color = Color(0xFFC7CFDD),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }

            if (dubs.size > 1) {
                Spacer(Modifier.height(14.dp))
                Text("Audio", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(dubs.size) { i ->
                        val d = dubs[i]
                        val on = d.id == activeId
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (on) Accent else Surface2)
                                .clickable(enabled = !busy && !on) {
                                    scope.launch {
                                        busy = true
                                        status = "Loading ${langLabel(d)} audio…"
                                        activeId = d.id
                                        load(d.id)
                                        status = null
                                        busy = false
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = langLabel(d),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { play(if (detail.isSeries) selSe else 0, if (detail.isSeries) selEp else 0) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            ) {
                Text(
                    text = if (detail.isSeries) "▶  Play S$selSe E$selEp" else "▶  Play",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = TextDim, fontSize = 12.sp)
            }

            if (detail.isSeries && seasons.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Seasons", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons.size) { i ->
                        val s = seasons[i]
                        val on = s.se == selSe
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) Accent else Surface2)
                                .clickable {
                                    selSe = s.se
                                    selEp = 1
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("S${s.se}", color = Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Episodes · tap to play", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                val maxEp = seasons.firstOrNull { it.se == selSe }?.maxEp ?: 0
                val rows = (1..maxEp).chunked(6)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { e ->
                                val on = e == selEp
                                Box(
                                    Modifier
                                        .size(46.dp, 40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (on) Accent else Surface2)
                                        .clickable {
                                            selEp = e
                                            play(selSe, e)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$e",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
