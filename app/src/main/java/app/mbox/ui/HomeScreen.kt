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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.HotSection
import app.mbox.R
import app.mbox.Store
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpen: (Subject) -> Unit,
    onResume: (app.mbox.HistoryEntry) -> Unit,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
    onMidnight: () -> Unit,
) {
    // Seed from Api's in-memory cache: returning to Home after Search or the player is instant
    // and costs no request, which is the whole point of keeping the response cache.
    var sections by remember { mutableStateOf(Api.cachedHotSections().orEmpty()) }
    var genres by remember { mutableStateOf(Api.cachedFilterOptions()?.genres.orEmpty()) }
    var category by rememberSaveable { mutableStateOf("All") }
    var genreItems by remember {
        mutableStateOf(
            if (category == "All") emptyList()
            else Api.cachedFiltered(genre = category).orEmpty()
        )
    }
    var genreLoading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(sections.isNotEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(Store.history().take(10)) }
    var midnight by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var showRank by remember { mutableStateOf(false) }
    var showBrowse by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (sections.isEmpty()) {
            val hot = runCatching { Api.hotSections() }.getOrNull()
            if (hot.isNullOrEmpty()) {
                error = "Couldn't reach MovieBox. Switch tabs and come back."
            } else {
                sections = hot
            }
        }
        if (genres.isEmpty()) {
            genres = runCatching { Api.filterOptions().genres }.getOrDefault(emptyList())
        }
        if (!Store.familyMode) {
            midnight = runCatching { Api.midnight(1).items }.getOrDefault(emptyList())
        }
        history = Store.history().take(10)
        loaded = true
    }

    var familySeen by remember { mutableStateOf(Store.familyEpoch) }
    LaunchedEffect(Store.familyEpoch) {
        if (familySeen == Store.familyEpoch) return@LaunchedEffect
        familySeen = Store.familyEpoch
        // The cache was cleared with the flag; every list must be fetched again. While the new
        // answer is in flight, drop the rows that are now illegal instead of showing them.
        if (Store.familyMode) {
            sections = sections.map { sec -> sec.copy(items = sec.items.filter { !it.adult }) }
            genreItems = genreItems.filter { !it.adult }
            midnight = emptyList()
        }
        error = null
        sections = runCatching { Api.hotSections() }.getOrDefault(sections)
        genres = runCatching { Api.filterOptions().genres }.getOrDefault(genres)
        if (!Store.familyMode) {
            midnight = runCatching { Api.midnight(1).items }.getOrDefault(emptyList())
        }
        if (category != "All") {
            genreLoading = true
            genreItems = runCatching { Api.filtered(subjectType = 0, genre = category) }
                .getOrDefault(emptyList())
            genreLoading = false
        }
        history = Store.history().take(10)
        loaded = true
    }

    LaunchedEffect(category) {
        if (category == "All") {
            genreItems = emptyList()
            return@LaunchedEffect
        }
        // Chips visited earlier are already in memory; only an unseen genre shows a spinner.
        val cached = Api.cachedFiltered(genre = category)
        if (cached != null) {
            genreItems = cached
            genreLoading = false
            return@LaunchedEffect
        }
        genreLoading = true
        genreItems = runCatching { Api.filtered(subjectType = 0, genre = category) }
            .getOrDefault(emptyList())
        genreLoading = false
    }

    val all = remember(sections) { sections.flatMap { it.items }.distinctBy { it.id } }
    val movies = remember(sections) {
        sections.firstOrNull { it.name.contains("Movie", true) }?.items
            ?: all.filter { it.type == 1 }
    }

    if (showRank) {
        RankScreen(onBack = { showRank = false }, onOpen = onOpen)
        return
    }

    if (showBrowse) {
        BrowseScreen(
            onBack = { showBrowse = false },
            onOpen = onOpen,
            startGenre = category.takeIf { it != "All" }.orEmpty(),
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(onSearch = onSearch, onDownloads = onDownloads)
        GenreStrip(
            items = listOf("All") + genres,
            selected = category,
            onSelect = { category = it },
            onBrowse = { showBrowse = true },
        )

        if (!loaded) {
            LoadingBox()
            return@Column
        }
        error?.let {
            MessageBox(it)
            return@Column
        }

        if (category != "All") {
            // A lazy grid only composes visible posters; the old chunked rows built the whole list.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(category) }
                when {
                    genreLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) { LoadingBox(Modifier.height(60.dp)) }
                    }

                    genreItems.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        MessageBox("Nothing in $category right now.")
                    }

                    else -> items(genreItems.size) { i ->
                        val s = genreItems[i]
                        PosterCard(s, Modifier.fillMaxWidth()) { onOpen(s) }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                run {
                    val hero = movies.ifEmpty { all }.take(6)
                    if (hero.isNotEmpty()) {
                        item { HeroPager(hero, onOpen) }
                    }
                    if (!Store.familyMode && midnight.isNotEmpty()) {
                        item { SectionHeader("🔞 Midnight", "See all") { onMidnight() } }
                        item { HorizontalPosterRow(midnight.take(12), onOpen) }
                    }
                    item { TipBanner(onDownloads) }
                    if (history.isNotEmpty()) {
                        item { SectionHeader("Continue Watching") }
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(history.size) { i ->
                                    HistoryCard(history[i]) { h -> onResume(h) }
                                }
                            }
                        }
                    }
                    if (movies.isNotEmpty()) {
                        item { SectionHeader("Top 10 this week", "Rankings") { showRank = true } }
                        item { HorizontalPosterRow(movies.take(10), onOpen, showRank = true) }
                        item { SectionHeader("Popular Movies") }
                        item { PosterGrid(movies.drop(10).ifEmpty { movies }.take(18), onOpen) }
                    }
                    sections.filter { it.name.contains("Movie", true).not() && it.items.isNotEmpty() }
                        .take(6)
                        .forEach { sec ->
                            item { SectionHeader(sec.name) }
                            item { HorizontalPosterRow(sec.items, onOpen) }
                        }
                    val series = all.filter { it.type == 2 }
                    if (series.isNotEmpty()) {
                        item { SectionHeader("Trending Series") }
                        item { HorizontalPosterRow(series.distinctBy { it.id }.take(20), onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPager(items: List<Subject>, onOpen: (Subject) -> Unit) {
    val pager = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(pager) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            delay(5000)
            val next = (pager.currentPage + 1) % items.size
            pager.animateScrollToPage(next)
        }
    }
    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val s = items[page]
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.78f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface2)
                    .clickable { onOpen(s) },
            ) {
                AsyncImage(
                    model = s.cover,
                    contentDescription = s.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x00000000), Color(0x33000000), Color(0xE6000000))
                            )
                        )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text(
                        s.title,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOf(s.year, s.genre.substringBefore(","), if (s.isSeries) "Series" else "Movie")
                            .filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = TextDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .clickable { onOpen(s) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            painter = painterResource(R.drawable.ic_player_play),
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("Play", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .size(if (i == pager.currentPage) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == pager.currentPage) AccentSoft
                            else Color(0x66FFFFFF)
                        ),
                )
            }
        }
    }
}

@Composable
private fun TipBanner(onDownloads: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF17231C))
            .clickable(onClick = onDownloads)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(R.drawable.ic_movie_download),
            contentDescription = null,
            tint = AccentSoft,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text("Download and watch offline", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("Full quality · no account needed", color = TextMute, fontSize = 10.sp)
        }
        Text("Open", color = AccentSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HistoryCard(h: app.mbox.HistoryEntry, onClick: (app.mbox.HistoryEntry) -> Unit) {
    Column(Modifier.width(156.dp).clickable { onClick(h) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(5.dp))
                .background(Surface2),
        ) {
            AsyncImage(
                model = h.still.ifBlank { h.cover },
                contentDescription = h.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color(0x66FFFFFF))
            )
            Box(
                Modifier
                    .fillMaxWidth(h.progress)
                    .height(2.5.dp)
                    .align(Alignment.BottomCenter)
                    .background(Accent)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(h.title, color = TextMain, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            buildString {
                if (h.type == 2) append("S${h.se} E${h.ep}  ·  ")
                append("${(h.progress * 100).toInt()}% watched")
            },
            color = TextMute,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}
