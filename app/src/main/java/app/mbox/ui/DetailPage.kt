package app.mbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.mbox.Staff
import app.mbox.Subject
import coil.compose.AsyncImage

/**
 * Full detail page in the stock app's shape: 16:9 backdrop, title block, Play / Download, synopsis,
 * metadata and the cast strip. Reached from the player's "Info" action, so tapping a poster still
 * opens the player first (the flow we already had), but the full page is one tap away.
 */
@Composable
fun DetailPage(
    subject: Subject,
    seasonCount: Int,
    episodeLabel: String,
    onClose: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onPerson: (Staff) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Surface2),
            ) {
                AsyncImage(
                    model = subject.still.ifBlank { subject.cover },
                    contentDescription = subject.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x22000000), Color(0x66000000), Bg)
                            )
                        )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(
                        subject.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOf(
                            if (subject.isSeries) "TV Series" else "Movie",
                            subject.year,
                            subject.country,
                            subject.contentRating,
                        ).filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = TextDim,
                        fontSize = 11.sp,
                    )
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (subject.rating.isNotBlank() && subject.rating != "0") {
                        Text("★ ${subject.rating}", color = Score, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                    }
                    if (subject.genre.isNotBlank()) {
                        Text(
                            subject.genre,
                            color = TextMute,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Accent)
                            .clickable(onClick = onPlay)
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("▶  Play", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Surface2)
                            .clickable(onClick = onDownload)
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Download", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (subject.desc.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        subject.desc,
                        color = TextDim,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))
                InfoRow("Duration", subject.duration)
                InfoRow("Language", subject.language)
                InfoRow("Content rating", subject.contentRating)
                InfoRow("Views", subject.viewers)
                InfoRow("Also known as", subject.aka)
                InfoRow("Seasons", if (seasonCount > 0) "$seasonCount" else "")
                InfoRow("Playing", episodeLabel)

                if (subject.staff.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Cast & Crew", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(subject.staff.size) { i ->
                            PersonCard(subject.staff[i]) { onPerson(subject.staff[i]) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        CircleBack(onClose, Modifier.align(Alignment.TopStart).padding(10.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextMute, fontSize = 11.sp, modifier = Modifier.width(120.dp))
        Text(value, color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}
