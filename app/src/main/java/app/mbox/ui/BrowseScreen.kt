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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.FilterOptions
import app.mbox.R
import app.mbox.Subject
import kotlinx.coroutines.launch

/**
 * Full catalog browser: genre chips plus the country / year / sort sheet the web filter page
 * exposes. The API already supports all four filters; before this screen only search used them.
 */
@Composable
fun BrowseScreen(
    onBack: () -> Unit,
    onOpen: (Subject) -> Unit,
    startGenre: String = "",
) {
    var options by remember { mutableStateOf(FilterOptions()) }
    var genre by remember { mutableStateOf(startGenre) }
    var country by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        options = runCatching { Api.filterOptions() }.getOrDefault(FilterOptions())
    }

    LaunchedEffect(genre, country, year, sort) {
        loading = true
        page = 1
        items = runCatching {
            Api.filtered(subjectType = 0, genre = genre, country = country, year = year, sort = sort, page = 1)
        }.getOrDefault(emptyList())
        more = items.size >= 30
        loading = false
    }

    fun loadMore() {
        if (loading || !more) return
        loading = true
        val next = page + 1
        scope.launch {
            val add = runCatching {
                Api.filtered(0, genre, country, year, sort, next)
            }.getOrDefault(emptyList())
            if (add.isEmpty()) {
                more = false
            } else {
                page = next
                items = items + add
                more = add.size >= 30
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
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
            Text(
                "Browse",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Filters",
                color = AccentSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { showFilter = true }.padding(8.dp),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Chip("All", genre.isBlank()) { genre = "" }
            }
            items(options.genres.size) { i ->
                val g = options.genres[i]
                Chip(g, g == genre) { genre = if (genre == g) "" else g }
            }
        }

        val summary = listOfNotNull(
            country.takeIf { it.isNotBlank() },
            year.takeIf { it.isNotBlank() },
            sort.takeIf { it.isNotBlank() },
        )
        if (summary.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                summary.forEach { value ->
                    Text(
                        "$value  ✕",
                        color = TextDim,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .clickable {
                                if (value == country) country = ""
                                if (value == year) year = ""
                                if (value == sort) sort = ""
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        when {
            loading && items.isEmpty() -> LoadingBox()
            !loading && items.isEmpty() -> MessageBox("No titles match these filters.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
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
                            loading -> Text("Loading…", color = TextMute, fontSize = 12.sp)
                            more -> Text(
                                "Load more",
                                color = AccentSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Surface1)
                                    .clickable { loadMore() }
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                            )
                            else -> Text("That's everything", color = Surface3, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (showFilter) {
        FilterDialog(
            options = options,
            genre = genre,
            country = country,
            year = year,
            sort = sort,
            onDismiss = { showFilter = false },
            onApply = { g, c, y, s ->
                genre = g
                country = c
                year = y
                sort = s
                showFilter = false
            },
        )
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) Color.Black else TextDim,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (active) Accent else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}
