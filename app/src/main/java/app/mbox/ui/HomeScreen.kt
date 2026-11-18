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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.HotSection
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpen: (Subject) -> Unit,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
) {
    var sections by remember { mutableStateOf<List<HotSection>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf("All") }

    LaunchedEffect(Unit) {
        Api.ensureToken()
        val hot = runCatching { Api.hotSections() }.getOrNull()
        if (hot.isNullOrEmpty()) {
            error = "Couldn't reach MovieBox. Pull again in a moment."
        } else {
            sections = hot
        }
    }

    val movies = sections.firstOrNull { it.name.contains("Movie", true) }?.items.orEmpty()
    val series = sections.firstOrNull { it.name.contains("Series", true) }?.items.orEmpty()
    val shorts = sections.firstOrNull { it.name.contains("Short", true) }?.items.orEmpty()
    val music = sections.firstOrNull { it.name.contains("Music", true) }?.items.orEmpty()

    val genres = remember(movies) {
        movies.flatMap { it.genre.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
            .take(8)
    }
    val filteredMovies = remember(movies, category) {
        if (category == "All") movies else movies.filter { it.genre.contains(category, true) }
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            HomeTopBar(onSearch = onSearch, onDownloads = onDownloads)
        }
        item {
            CategoryStrip(
                categories = listOf("All") + genres,
                selected = category,
                onSelect = { category = it },
            )
        }
        if (movies.isNotEmpty()) {
            item {
                HeroCarousel(movies.take(6), onOpen)
            }
        }
        item {
            TipBanner(onDownloads)
        }
        if (filteredMovies.isNotEmpty()) {
            item {
                SectionTitle("Trending Movies", onAll = { onOpen(filteredMovies.first()) })
            }
            item {
                PosterGrid(filteredMovies, onOpen)
            }
        }
        if (series.isNotEmpty()) {
            item { SectionTitle("Trending Series") }
            item { PosterRow(series, onOpen) }
        }
        if (shorts.isNotEmpty()) {
            item { SectionTitle("Short TV") }
            item { PosterRow(shorts, onOpen) }
        }
        if (music.isNotEmpty()) {
            item { SectionTitle("Music") }
            item { PosterRow(music, onOpen) }
        }
        error?.let { msg ->
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(msg, color = TextDim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(onSearch: () -> Unit, onDownloads: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, contentDescription = "Profile", tint = TextDim, modifier = Modifier.size(20.dp))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface1)
                .clickable(onClick = onSearch)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = TextDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Search movies, series, anime…", color = TextDim, fontSize = 13.sp, maxLines = 1)
        }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Surface1).clickable(onClick = onDownloads),
            contentAlignment = Alignment.Center,
        ) {
            Text("⤓", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CategoryStrip(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth().background(Bg).padding(bottom = 10.dp),
    ) {
        items(categories.size) { i ->
            val name = categories[i]
            val on = name == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = name,
                    color = if (on) TextMain else TextDim,
                    fontSize = if (on) 16.sp else 15.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { onSelect(name) },
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .height(3.dp)
                        .width(if (on) 20.dp else 0.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (on) Accent else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun HeroCarousel(items: List<Subject>, onOpen: (Subject) -> Unit) {
    val pager = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(pager) {
        while (true) {
            delay(6000)
            if (items.size > 1) {
                val next = (pager.currentPage + 1) % items.size
                pager.animateScrollToPage(next)
            }
        }
    }
    Column {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 14.dp),
            pageSpacing = 10.dp,
        ) { index ->
            val item = items[index]
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .clickable { onOpen(item) },
            ) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color(0x00000000),
                            0.55f to Color(0x66000000),
                            1f to Color(0xF20B0D12),
                        )
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(14.dp),
                ) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOf(item.year, item.genre.substringBefore(","), item.country)
                            .filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = Color(0xFFCBD3E1),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(Accent)
                            .clickable { onOpen(item) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("▶", color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("Play", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == pager.currentPage) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) Accent else Surface2)
                )
            }
        }
    }
}

@Composable
private fun TipBanner(onDownloads: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF2B2113), Color(0xFF3A2C16)))
            )
            .clickable(onClick = onDownloads)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color(0x33FFC107)),
            contentAlignment = Alignment.Center,
        ) { Text("⤓", color = Color(0xFFFFC107), fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Download and watch offline", color = Color(0xFFFFE0A3), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Any title, any quality · no ads", color = Color(0xFFB9A683), fontSize = 11.sp)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFFFE9B8))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Text("Open", color = Color(0xFF2B2113), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SectionTitle(title: String, onAll: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onAll != null) {
            Text("All ›", color = TextDim, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onAll))
        }
    }
}

@Composable
private fun PosterGrid(items: List<Subject>, onOpen: (Subject) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                row.forEach { item ->
                    PosterTile(item, Modifier.weight(1f), onOpen)
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PosterRow(items: List<Subject>, onOpen: (Subject) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items.size) { i -> PosterTile(items[i], Modifier.width(104.dp), onOpen) }
    }
}

@Composable
private fun PosterTile(item: Subject, modifier: Modifier = Modifier, onOpen: (Subject) -> Unit) {
    Column(modifier.clickable { onOpen(item) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2),
        ) {
            AsyncImage(
                model = item.cover,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val badge = item.badge.ifBlank { if (item.title.contains("[Hindi]", true)) "Hindi" else "" }
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = item.title,
            color = TextMain,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
