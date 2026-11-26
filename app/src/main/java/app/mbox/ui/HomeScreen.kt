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
import app.mbox.HomeFeed
import app.mbox.HomeFilter
import app.mbox.R
import app.mbox.Store
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Home is not our own layout: `/home?host=…` returns the stock app's ordered `operatingList`
 * (banner, "Popular Series", "Popular Movie", "Categories", …). Drawing that list in order is
 * what makes the rows, their titles and their order match the real app. There is deliberately no
 * "Continue Watching" here — the stock app keeps history under Me/Settings.
 */
@Composable
fun HomeScreen(
    onOpen: (Subject) -> Unit,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
) {
    var feed by remember { mutableStateOf(Api.cachedHomeFeed() ?: HomeFeed()) }
    var loading by remember { mutableStateOf(feed.sections.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showBrowse by remember { mutableStateOf(false) }
    var browseFilter by remember { mutableStateOf<HomeFilter?>(null) }

    LaunchedEffect(Store.familyEpoch) {
        loading = feed.sections.isEmpty()
        error = null
        feed = try {
            Api.homeFeed()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HomeFeed()
        }
        if (feed.sections.isEmpty()) {
            error = "Couldn't reach MovieBox. Pull a tab and come back."
        }
        loading = false
    }

    if (showBrowse) {
        val f = browseFilter
        BrowseScreen(
            onBack = { showBrowse = false },
            onOpen = onOpen,
            startGenre = f?.genre.orEmpty(),
            startCountry = f?.country.orEmpty(),
            startYear = f?.year.orEmpty(),
            startSort = f?.sort.orEmpty(),
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(onSearch = onSearch, onDownloads = onDownloads)

        when {
            loading -> LoadingBox()
            error != null && feed.sections.isEmpty() -> MessageBox(error.orEmpty())
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (feed.banners.isNotEmpty()) {
                    item { HeroPager(feed.banners, onOpen) }
                }
                feed.sections.forEach { section ->
                    if (section.filters.isNotEmpty()) {
                        item { SectionHeader(section.title) }
                        item {
                            CategoryRow(section.filters) { filter ->
                                browseFilter = filter
                                showBrowse = true
                            }
                        }
                    } else if (section.items.isNotEmpty()) {
                        item { SectionHeader(section.title) }
                        item { HorizontalPosterRow(section.items, onOpen) }
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
                    model = s.still.ifBlank { s.cover },
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

/** The stock app's "Categories" cards (Hollywood, Bollywood, Western, …). */
@Composable
private fun CategoryRow(filters: List<HomeFilter>, onClick: (HomeFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters.size) { i ->
            val f = filters[i]
            Box(
                Modifier
                    .width(148.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface2)
                    .clickable { onClick(f) },
            ) {
                AsyncImage(
                    model = f.image,
                    contentDescription = f.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x22000000), Color(0xCC000000))
                            )
                        )
                )
                Text(
                    f.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }
    }
}
