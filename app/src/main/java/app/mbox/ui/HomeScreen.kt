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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import coil.compose.AsyncImage
import app.mbox.Api
import app.mbox.HotSection
import app.mbox.Subject

@Composable
fun HomeScreen(onOpen: (Subject) -> Unit, onSearch: () -> Unit) {
    var sections by remember { mutableStateOf<List<HotSection>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        Api.ensureToken()
        runCatching { Api.hotSections() }
            .onSuccess { sections = it }
            .onFailure { error = it.message }
        runCatching { Api.trending(1) }
            .onSuccess { first ->
                val more = runCatching { Api.trending(2) }.getOrDefault(emptyList())
                trending = (first + more).distinctBy { it.id }
            }
        loading = false
    }

    when {
        loading -> LoadingBox()
        sections.isEmpty() && trending.isEmpty() ->
            MessageBox(error ?: "Could not load catalog. Check your connection.")
        else -> Catalog(
            sections = sections,
            trending = trending,
            onOpen = onOpen,
            onSearch = onSearch,
        )
    }
}

@Composable
private fun Catalog(
    sections: List<HotSection>,
    trending: List<Subject>,
    onOpen: (Subject) -> Unit,
    onSearch: () -> Unit,
) {
    val hero = remember(sections) { sections.firstOrNull()?.items?.take(6).orEmpty() }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { TopBar(onSearch = onSearch) }
        if (hero.isNotEmpty()) {
            item { HeroPager(hero, onOpen) }
        }
        sections.forEach { section ->
            item { SectionHeader(section.name) }
            item { HorizontalPosterRow(section.items) { onOpen(it) } }
        }
        if (trending.isNotEmpty()) {
            item { SectionHeader("Trending Now") }
            items(trending.chunked(3).size) { index ->
                val row = trending.chunked(3)[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { s ->
                        PosterCard(s, modifier = Modifier.weight(1f)) { onOpen(s) }
                    }
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPager(items: List<Subject>, onOpen: (Subject) -> Unit) {
    val pager = rememberPagerState { items.size }
    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(215.dp)) { page ->
            val s = items[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onOpen(s) },
            ) {
                AsyncImage(
                    model = s.cover,
                    contentDescription = s.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Bg.copy(alpha = 0.35f),
                                1f to Bg,
                            )
                        ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Text(
                        text = s.title,
                        color = Color.White,
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOf(s.year, s.genre, s.duration).filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = TextDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .size(if (i == pager.currentPage) 14.dp else 6.dp, 6.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) Accent else Color(0x66FFFFFF)),
                )
            }
        }
    }
}
