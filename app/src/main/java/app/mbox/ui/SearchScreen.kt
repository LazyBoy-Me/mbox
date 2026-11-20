package app.mbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.FilterOptions
import app.mbox.HotSection
import app.mbox.R
import app.mbox.SearchGroup
import app.mbox.SearchTab
import app.mbox.Store
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(onOpen: (Subject) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("All") }
    var tabs by remember { mutableStateOf<List<SearchTab>>(emptyList()) }
    var groups by remember { mutableStateOf<List<SearchGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf(Store.searchHistory()) }

    // landing content
    var hotSections by remember { mutableStateOf<List<HotSection>>(emptyList()) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }

    // filters
    var options by remember { mutableStateOf(FilterOptions()) }
    var showFilter by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var fGenre by remember { mutableStateOf("") }
    var fCountry by remember { mutableStateOf("") }
    var fYear by remember { mutableStateOf("") }
    var fSort by remember { mutableStateOf("") }
    var browseItems by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var person by remember { mutableStateOf<app.mbox.Staff?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) {
        val rank = runCatching { Api.searchRank() }.getOrNull()
        hotSections = rank?.sections.orEmpty()
        hotWords = rank?.words.orEmpty()
        options = runCatching { Api.filterOptions() }.getOrDefault(FilterOptions())
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            live = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        live = runCatching { Api.suggestWords(q) }.getOrDefault(emptyList())
    }

    LaunchedEffect(submitted, tab) {
        if (submitted.isBlank()) return@LaunchedEffect
        loading = true
        val page = runCatching { Api.searchV2(submitted, tab) }.getOrNull()
        tabs = page?.tabs.orEmpty()
        groups = page?.groups.orEmpty()
        loading = false
    }

    LaunchedEffect(browsing, fGenre, fCountry, fYear, fSort) {
        if (!browsing) return@LaunchedEffect
        loading = true
        browseItems = runCatching {
            Api.filtered(0, fGenre, fCountry, fYear, fSort)
        }.getOrDefault(emptyList())
        loading = false
    }

    fun submit(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        query = w
        submitted = w
        browsing = false
        live = emptyList()
        Store.addSearch(w)
        history = Store.searchHistory()
        // Enter on the keyboard (or tapping Search / a suggestion) closes the IME and drops focus.
        keyboard?.hide()
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // ------------------------------------------------------------ search bar
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_black_24),
                    contentDescription = "Back",
                    tint = TextMain,
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1E2025))
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = TextMute,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search movies, TV shows, people", color = TextMute, fontSize = 12.sp, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            browsing = false
                            if (it.isEmpty()) submitted = ""
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 13.sp),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(22.dp).clickable {
                            query = ""
                            submitted = ""
                            live = emptyList()
                            browsing = false
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ls_ic_close_20),
                            contentDescription = "Clear",
                            tint = TextMute,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Search",
                color = AccentSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { submit(query) }.padding(4.dp),
            )
        }

        when {
            browsing -> BrowseResults(
                title = listOf(fGenre, fCountry, fYear, fSort).filter { it.isNotBlank() }
                    .joinToString(" · ").ifBlank { "Browse" },
                items = browseItems,
                loading = loading,
                onOpen = onOpen,
                onFilter = { showFilter = true },
            )

            loading -> LoadingBox()

            submitted.isNotBlank() -> Results(
                tabs = tabs,
                selected = tab,
                groups = groups,
                onTab = { tab = it },
                onOpen = onOpen,
                onPerson = { person = it },
                onFilter = { showFilter = true },
            )

            live.isNotEmpty() && query.trim() != submitted.trim() -> LazyColumn {
                items(live.size) { i ->
                    SuggestionRow(live[i]) {
                        query = live[i]
                        submit(live[i])
                    }
                }
            }

            else -> Landing(
                history = history,
                words = hotWords,
                sections = hotSections,
                onWord = { submit(it) },
                onOpen = onOpen,
                onClearHistory = {
                    Store.clearSearchHistory()
                    history = emptyList()
                },
            )
        }
    }

    person?.let { p ->
        StaffSheet(
            staff = p,
            onDismiss = { person = null },
            onOpen = { onOpen(it) },
        )
    }

    if (showFilter) {
        FilterDialog(
            options = options,
            genre = fGenre,
            country = fCountry,
            year = fYear,
            sort = fSort,
            onDismiss = { showFilter = false },
            onApply = { g, c, y, s ->
                fGenre = g
                fCountry = c
                fYear = y
                fSort = s
                showFilter = false
                browsing = true
                submitted = ""
            },
        )
    }
}

@Composable
private fun Landing(
    history: List<String>,
    words: List<String>,
    sections: List<HotSection>,
    onWord: (String) -> Unit,
    onOpen: (Subject) -> Unit,
    onClearHistory: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        if (history.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Recent searches", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Clear", color = TextMute, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onClearHistory))
                }
            }
            item {
                Chips(history, onWord)
            }
        }
        if (words.isNotEmpty()) {
            item { SectionHeader("Trending searches") }
            item { Chips(words, onWord) }
        }
        sections.forEach { sec ->
            if (sec.items.isNotEmpty()) {
                item { SectionHeader(sec.name) }
                item { HorizontalPosterRow(sec.items, onOpen, showRank = true) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(words: List<String>, onClick: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        words.forEach { w -> SurfaceChip(w) { onClick(w) } }
    }
}

@Composable
private fun SuggestionRow(word: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = TextMute,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(word, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Results(
    tabs: List<SearchTab>,
    selected: String,
    groups: List<SearchGroup>,
    onTab: (String) -> Unit,
    onOpen: (Subject) -> Unit,
    onPerson: (app.mbox.Staff) -> Unit,
    onFilter: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val flat = tabs.map { SearchTab(it.id, it.name) }
                items(flat.size) { i ->
                    val t = flat[i]
                    val active = t.id == selected
                    Text(
                        t.name,
                        color = if (active) TextMain else TextMute,
                        fontSize = if (active) 15.sp else 14.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { onTab(t.id) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
            Row(
                Modifier.clickable(onClick = onFilter).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_home_tab_menu),
                    contentDescription = "Filters",
                    tint = TextMain,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (groups.isEmpty()) {
            MessageBox("No results. Try another spelling or a shorter word.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { group ->
                if (group.title.isNotBlank() && groups.size > 1 && (group.items.isNotEmpty() || group.people.isNotEmpty())) {
                    item { SectionHeader(group.title) }
                }
                if (group.people.isNotEmpty()) {
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(group.people.size) { i ->
                                PersonCard(group.people[i]) { onPerson(group.people[i]) }
                            }
                        }
                    }
                }
                items(group.items.size) { i ->
                    ResultRow(group.items[i], if (selected == "All") i + 1 else 0) { onOpen(group.items[i]) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(s: Subject, rank: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank > 0) {
            Text(
                "$rank",
                color = if (rank <= 3) AccentSoft else TextMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp),
            )
        }
        Box(
            Modifier
                .size(56.dp, 80.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Surface2),
        ) {
            AsyncImage(
                model = s.cover,
                contentDescription = s.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (s.badge.isNotBlank()) {
                Text(
                    s.badge,
                    color = Color.White,
                    fontSize = 8.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.title,
                color = TextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                listOf(
                    if (s.isSeries) "TV Series" else "Movie",
                    s.year,
                    s.country,
                    s.genre.substringBefore(","),
                    s.duration,
                ).filter { it.isNotBlank() }.joinToString(" · "),
                color = TextMute,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (s.rating.isNotBlank() && s.rating != "0") {
                Spacer(Modifier.height(3.dp))
                Text("★ ${s.rating}", color = Score, fontSize = 10.sp)
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_player_play),
            contentDescription = null,
            tint = TextMute,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun BrowseResults(
    title: String,
    items: List<Subject>,
    loading: Boolean,
    onOpen: (Subject) -> Unit,
    onFilter: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Filter", color = AccentSoft, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onFilter))
        }
        when {
            loading -> LoadingBox()
            items.isEmpty() -> MessageBox("No titles match these filters.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items.size) { i ->
                    PosterCard(items[i], Modifier.fillMaxWidth()) { onOpen(items[i]) }
                }
            }
        }
    }
}

@Composable
fun FilterDialog(
    options: FilterOptions,
    genre: String,
    country: String,
    year: String,
    sort: String,
    onDismiss: () -> Unit,
    onApply: (String, String, String, String) -> Unit,
) {
    var g by remember { mutableStateOf(genre) }
    var c by remember { mutableStateOf(country) }
    var y by remember { mutableStateOf(year) }
    var s by remember { mutableStateOf(sort.ifBlank { options.sorts.firstOrNull() ?: "" }) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF17191E))
                .padding(14.dp),
        ) {
            Text("Filters", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            FilterGroup("Genre", listOf("") + options.genres, g) { g = it }
            FilterGroup("Country", listOf("") + options.countries, c) { c = it }
            FilterGroup("Year", listOf("") + options.years, y) { y = it }
            FilterGroup("Sort by", options.sorts, s) { s = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Reset",
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Surface2)
                        .clickable { g = ""; c = ""; y = ""; s = "" }
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    "Apply",
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent)
                        .clickable { onApply(g, c, y, s) }
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (values.isEmpty()) return
    Column(Modifier.padding(top = 10.dp)) {
        Text(title, color = TextMute, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { v ->
                val active = v == selected
                Text(
                    v.ifBlank { "All" },
                    color = if (active) Color.Black else TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (active) Accent else Surface2)
                        .clickable { onSelect(v) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}
