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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.MidnightPage
import app.mbox.Store
import app.mbox.Subject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The 18+ shelf. The stock app mixes these rows into the normal catalog and hides them with
 * Family Mode; this tab is only reachable while Family Mode is off and disappears again when it
 * is switched back on (MainActivity resets the selected tab).
 */
@Composable
fun MidnightScreen(onOpen: (Subject) -> Unit) {
    val sorts = listOf("Hottest", "Latest", "Rating")
    var sort by rememberSaveable { mutableStateOf(sorts.first()) }
    var items by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sort, Store.familyEpoch) {
        if (Store.familyMode) {
            items = emptyList()
            more = false
            loading = false
            return@LaunchedEffect
        }
        page = 1
        items = emptyList()
        loading = true
        val first = try {
            Api.midnight(1, sort)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            MidnightPage()
        }
        items = first.items
        more = first.hasMore
        loading = false
    }

    fun loadMore() {
        if (loading || !more || Store.familyMode) return
        loading = true
        val next = page + 1
        scope.launch {
            val add = try {
                Api.midnight(next, sort)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MidnightPage()
            }
            if (add.items.isEmpty()) {
                more = false
            } else {
                page = next
                items = items + add.items.filter { s -> items.none { it.id == s.id } }
                more = add.hasMore
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF2A0E12), Color(0xFF16181D), Color(0xFF16181D))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔞", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Midnight",
                        color = TextMain,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "18+",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFFE53935))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "Adults only. Family Mode hides this shelf.",
                    color = TextMute,
                    fontSize = 11.sp,
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sorts.size) { i ->
                MidnightChip(sorts[i], sorts[i] == sort) { sort = sorts[i] }
            }
        }

        when {
            Store.familyMode -> MessageBox(
                "Family Mode is ON. Turn it off in Me to see 18+ titles."
            )

            loading && items.isEmpty() -> LoadingBox()

            !loading && items.isEmpty() -> MessageBox(
                "Nothing here right now. The 18+ feed may be empty for this region."
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items.size) { i ->
                    PosterCard(items[i], Modifier.fillMaxWidth()) { onOpen(items[i]) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loading -> LoadingBox(Modifier.height(40.dp))
                            more -> Text(
                                "Load more",
                                color = AccentSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Surface1)
                                    .clickable { loadMore() }
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                            )

                            items.isNotEmpty() -> Text(
                                "That's everything.",
                                color = TextMute,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MidnightChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) Color.White else TextDim,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (active) Color(0xFFB3261E) else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
