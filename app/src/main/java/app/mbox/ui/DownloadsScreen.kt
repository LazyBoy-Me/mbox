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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Downloads
import app.mbox.PlaySpec

@Composable
fun DownloadsScreen(onOpen: (PlaySpec) -> Unit) {
    val ctx = LocalContext.current
    val tasks by Downloads.tasks.collectAsState()

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Downloads", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (tasks.isNotEmpty()) {
                Text(
                    "Clear finished",
                    color = Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        tasks.filter { it.finished }.forEach { Downloads.remove(ctx, it.id) }
                    },
                )
            }
        }

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⤓", color = Surface2, fontSize = 56.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing downloaded yet", color = TextDim, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Open a title → Download → pick a quality", color = TextDim, fontSize = 11.sp)
                }
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp)) {
            items(tasks.size) { i ->
                val t = tasks[i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface1)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title,
                                color = TextMain,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(t.subtitle, color = TextDim, fontSize = 11.sp)
                        }
                        if (t.finished) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Accent).clickable {
                                    onOpen(
                                        PlaySpec(
                                            baseId = "",
                                            title = t.title,
                                            localPath = t.path,
                                        )
                                    )
                                },
                                contentAlignment = Alignment.Center,
                            ) { Text("▶", color = Color.White, fontSize = 13.sp) }
                        } else {
                            Text(
                                if (t.running) "Cancel" else "Remove",
                                color = TextDim,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    if (t.running) Downloads.cancel(t.id) else Downloads.remove(ctx, t.id)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { t.progress },
                        color = if (t.finished) Color(0xFF2BD67B) else Accent,
                        trackColor = Surface2,
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                t.finished -> "Completed · ${fmtBytes(t.done)}"
                                t.status == "failed" -> "Failed · tap Remove to retry"
                                t.status == "canceled" -> "Canceled"
                                else -> "${fmtBytes(t.done)} / ${fmtBytes(t.total)}"
                            },
                            color = TextDim,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        if (t.running && t.speed > 0) {
                            Text(
                                "${fmtSpeed(t.speed)} · ${t.speedLabel}",
                                color = Color(0xFF2BD67B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (t.etaLabel.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(t.etaLabel, color = TextDim, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

private fun fmtSpeed(bps: Long): String = when {
    bps >= 1_000_000 -> String.format("%.1f MB/s", bps / 1_000_000.0)
    else -> "${bps / 1000} KB/s"
}
