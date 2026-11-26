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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(onOpen: (Subject) -> Unit, onBack: () -> Unit) {
    // Saveable so leaving Search for Home and coming back keeps the query, the chosen tab and
    // the result list; the cached page below is then already there on the first frame.
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("All") }
    var tabs by remember {
        mutableStateOf(
            if (submitted.isBlank()) emptyList()
            else Api.cachedSearchV2(submitted, tab)?.tabs.orEmpty()
        )
    }
    var groups by remember {
        mutableStateOf(
            if (submitted.isBlank()) emptyList()
            else Api.cachedSearchV2(submitted, tab)?.groups.orEmpty()
        )
    }
    var loading by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<String>>(emptyList()) }
    var liveLoading by remember { mutableStateOf(false) }
    // Every answer ever received this session; typing/backspacing is filtered locally first.
    var pool by remember { mutableStateOf<List<String>>(emptyList()) }
    var liveSubjects by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var browseAll by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(Store.searchHistory()) }

    // landing content, seeded from the cache so revisiting the tab draws instantly
    var hotSections by remember { mutableStateOf(Api.cachedSearchRank()?.sections.orEmpty()) }
    var hotWords by remember { mutableStateOf(Api.cachedSearchRank()?.words.orEmpty()) }

    // filters
    var options by remember { mutableStateOf(Api.cachedFilterOptions() ?: FilterOptions()) }
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
        if (hotSections.isEmpty() && hotWords.isEmpty()) {
            val rank = try {
                Api.searchRank()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            hotSections = rank?.sections.orEmpty()
            hotWords = rank?.words.orEmpty()
        }
        if (options.genres.isEmpty()) {
            options = try {
                Api.filterOptions()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FilterOptions()
            }
        }
    }

    var familySeen by remember { mutableStateOf(Store.familyEpoch) }
    LaunchedEffect(Store.familyEpoch) {
        // Family Mode hides 18+ rows everywhere; the effects below re-ask because their keys
        // include the epoch and Store cleared the response cache.
        if (familySeen == Store.familyEpoch) return@LaunchedEffect
        familySeen = Store.familyEpoch
        pool = emptyList()
        live = emptyList()
        liveSubjects = emptyList()
        if (Store.familyMode) {
            // Hide already-rendered 18+ rows immediately, before the refreshed answer lands.
            groups = groups.map { g -> g.copy(items = g.items.filter { !it.adult }) }
            browseItems = browseItems.filter { !it.adult }
        }
    }

    // Live suggestion list. Typing must never wait for the network: the pool we already have is
    // filtered locally on every key press, the request is debounced, and results are merged only
    // when they belong to the query that is still in the box.
    LaunchedEffect(query, Store.familyEpoch) {
        val q = query.trim()
        if (q.isEmpty()) {
            live = emptyList()
            liveLoading = false
            return@LaunchedEffect
        }
        val local = Api.rankWords(live + pool + history, q)
        if (local.isNotEmpty()) live = local
        // The endpoint answers even for one letter (mostly studio/adult junk), so keep the adult
        // filter on and give the single-key case a little more time before asking.
        if (local.isEmpty()) liveLoading = true
        delay(if (q.length < 2) 300 else 140)
        try {
            val fresh = Api.suggestWords(q)
            if (query.trim() != q) return@LaunchedEffect
            pool = (fresh + pool).distinctBy { it.lowercase() }.take(80)
            val ranked = Api.rankWords(fresh, q)
            if (ranked.isNotEmpty() || local.isEmpty()) live = ranked
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            if (query.trim() == q) liveLoading = false
        }
    }

    // Poster "top results" are expensive (two search calls), so they arrive after the word list
    // and are allowed to be cancelled by the next keystroke without clearing the suggestions.
    LaunchedEffect(query, submitted, Store.familyEpoch) {
        val q = query.trim()
        if (q.length < 2 || q == submitted.trim()) {
            liveSubjects = emptyList()
            return@LaunchedEffect
        }
        delay(450)
        try {
            val hits = Api.search(q)
            if (query.trim() == q) liveSubjects = hits.take(3)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(submitted, tab, Store.familyEpoch) {
        if (submitted.isBlank()) return@LaunchedEffect
        Api.cachedSearchV2(submitted, tab)?.let { page ->
            tabs = page.tabs
            groups = page.groups
            loading = false
            return@LaunchedEffect
        }
        loading = groups.isEmpty()
        val page = try {
            Api.searchV2(submitted, tab)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        tabs = page?.tabs.orEmpty()
        groups = page?.groups.orEmpty()
        loading = false
    }

    LaunchedEffect(browsing, fGenre, fCountry, fYear, fSort, Store.familyEpoch) {
        if (!browsing) return@LaunchedEffect
        val cached = Api.cachedFiltered(0, fGenre, fCountry, fYear, fSort)
        if (cached != null) {
            browseItems = cached
            loading = false
            return@LaunchedEffect
        }
        loading = browseItems.isEmpty()
        browseItems = try {
            Api.filtered(0, fGenre, fCountry, fYear, fSort)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        loading = false
    }

    fun submit(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        query = w
        submitted = w
        browsing = false
        live = emptyList()
        liveSubjects = emptyList()
        liveLoading = false
        // A new submit must not paint the previous query's hits while the new page loads.
        groups = emptyList()
        tabs = emptyList()
        loading = true
        Store.addSearch(w)
        history = Store.searchHistory()
        // Enter on the keyboard (or tapping Search / a suggestion) closes the IME and drops focus.
        keyboard?.hide()
        focus.clearFocus()
    }

    if (browseAll) {
        BrowseScreen(onBack = { browseAll = false }, onOpen = onOpen)
        return
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
                            if (it.isEmpty()) {
                                submitted = ""
                                groups = emptyList()
                                tabs = emptyList()
                                loading = false
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 13.sp),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (liveLoading) {
                    CircularProgressIndicator(
                        color = AccentSoft,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(22.dp).clickable {
                            query = ""
                            submitted = ""
                            live = emptyList()
                            liveSubjects = emptyList()
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

            // Typing after a search must show the new suggestions, not the previous result page.
            query.trim().isNotEmpty() && query.trim() != submitted.trim() -> LiveSuggestions(
                query = query.trim(),
                words = live,
                subjects = liveSubjects,
                loading = liveLoading,
                onOpen = onOpen,
                onWord = { word ->
                    query = word
                    submit(word)
                },
                onSubmit = { submit(query) },
            )

            loading && groups.isEmpty() -> LoadingBox()

            submitted.isNotBlank() -> Results(
                tabs = tabs,
                selected = tab,
                groups = groups,
                onTab = { tab = it },
                onOpen = onOpen,
                onPerson = { person = it },
                onFilter = { showFilter = true },
            )

            else -> Landing(
                history = history,
                words = hotWords,
                sections = hotSections,
                onWord = { submit(it) },
                onOpen = onOpen,
                onBrowse = {
                    browseAll = true
                    keyboard?.hide()
                    focus.clearFocus()
                },
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
    onBrowse: () -> Unit,
    onClearHistory: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBrowse)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_home_tab_menu),
                    contentDescription = null,
                    tint = AccentSoft,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "Browse all titles",
                    color = TextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text("Genre · Country · Year", color = TextMute, fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right_white_small),
                    contentDescription = null,
                    tint = TextMute,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
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

/**
 * The live dropdown, in the stock app's order: poster top-results first (they arrive late), then
 * the full-title word rows with the typed part highlighted, then an explicit "see all" row.
 */
@Composable
private fun LiveSuggestions(
    query: String,
    words: List<String>,
    subjects: List<Subject>,
    loading: Boolean,
    onOpen: (Subject) -> Unit,
    onWord: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (subjects.isNotEmpty()) {
            item { SectionHeader("Top results") }
            items(subjects.size) { i ->
                ResultRow(subjects[i], 1) { onOpen(subjects[i]) }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Suggestions",
                    color = TextMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(
                        color = AccentSoft,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        if (words.isEmpty() && !loading) {
            item {
                Text(
                    "No title starts with “$query” — press Search to look inside descriptions.",
                    color = TextMute,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        items(words.size) { i ->
            SuggestionRow(words[i], query) { onWord(words[i]) }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onSubmit)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = AccentSoft,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "See all results for “$query”",
                    color = AccentSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(word: String, query: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = TextMute,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            HighlightedTitle(word, query)
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right_white_small),
            contentDescription = null,
            tint = TextMute,
            modifier = Modifier.size(11.dp),
        )
    }
}

@Composable
private fun HighlightedTitle(text: String, query: String) {
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    if (at < 0) {
        Text(text, color = TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        return
    }
    val styled = buildAnnotatedString {
        append(text.substring(0, at))
        withStyle(SpanStyle(color = AccentSoft, fontWeight = FontWeight.Bold)) {
            append(text.substring(at, at + q.length))
        }
        append(text.substring(at + q.length))
    }
    Text(styled, color = TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
