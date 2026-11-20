package app.mbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.R
import app.mbox.Staff
import app.mbox.Subject
import coil.compose.AsyncImage

/** Round cast portrait with the role underneath, as used by the detail page. */
@Composable
fun PersonCard(
    staff: Staff,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .width(72.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = staff.avatar,
            contentDescription = staff.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Surface2),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            staff.name,
            color = TextMain,
            fontSize = 10.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        if (staff.character.isNotBlank()) {
            Text(
                staff.character,
                color = TextMute,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Person sheet: headline, filmography (`staff-subject-list`) and related people. Tapping a title
 * closes the sheet and opens that title so the watch screen keeps a single player instance.
 */
@Composable
fun StaffSheet(
    staff: Staff,
    onDismiss: () -> Unit,
    onOpen: (Subject) -> Unit,
) {
    var info by remember(staff.id) { mutableStateOf(staff) }
    var titles by remember(staff.id) { mutableStateOf<List<Subject>>(emptyList()) }
    var related by remember(staff.id) { mutableStateOf<List<Staff>>(emptyList()) }
    var loading by remember(staff.id) { mutableStateOf(true) }

    LaunchedEffect(staff.id) {
        if (staff.id.isNotEmpty()) {
            runCatching { Api.staffInfo(staff.id) }.getOrNull()?.let {
                if (it.name.isNotBlank()) info = it
            }
            titles = runCatching { Api.staffSubjects(staff.id) }.getOrDefault(emptyList())
            related = runCatching { Api.staffRelated(staff.id) }.getOrDefault(emptyList())
        }
        loading = false
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF17191E)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = info.avatar,
                    contentDescription = info.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(54.dp).clip(CircleShape).background(Surface2),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.name, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (info.character.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(info.character, color = TextMute, fontSize = 11.sp, maxLines = 2)
                    }
                    if (titles.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text("${titles.size} titles", color = TextMute, fontSize = 10.sp)
                    }
                }
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ls_ic_close_20),
                    contentDescription = "Close",
                    tint = TextMute,
                    modifier = Modifier.size(16.dp).clickable(onClick = onDismiss),
                )
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    LoadingBox()
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (titles.isNotEmpty()) {
                        item { SheetTitle("Known for") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(titles.size) { i ->
                                    val s = titles[i]
                                    Column(
                                        Modifier.width(96.dp).clickable {
                                            onDismiss()
                                            onOpen(s)
                                        },
                                    ) {
                                        AsyncImage(
                                            model = s.cover,
                                            contentDescription = s.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(138.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Surface2),
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            s.title,
                                            color = TextMain,
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            listOf(s.year, if (s.isSeries) "Series" else "Movie")
                                                .filter { it.isNotBlank() }.joinToString(" · "),
                                            color = TextMute,
                                            fontSize = 9.sp,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        item { MessageBox("No filmography on MovieBox for this person.") }
                    }
                    if (related.isNotEmpty()) {
                        item { SheetTitle("Related people") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(related.size) { i -> PersonCard(related[i]) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        color = TextMain,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 8.dp),
    )
}
