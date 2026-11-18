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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.SearchTab
import app.mbox.Subject
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(initialQuery: String = "", onOpen: (Subject) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf(initialQuery) }
    var submitted by remember { mutableStateOf(initialQuery) }
    var tab by remember { mutableStateOf("All") }
    var tabs by remember { mutableStateOf<List<SearchTab>>(emptyList()) }
    var results by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val hot = runCatching { Api.hotSections() }.getOrDefault(emptyList())
        suggestions = hot.flatMap { it.items }.map { it.title }.distinct().take(10)
    }

    LaunchedEffect(submitted, tab) {
        val q = submitted.trim()
        if (q.isEmpty()) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        loading = true
        searched = true
        delay(150)
        val page = runCatching { Api.searchV2(q, tab) }.getOrNull()
        if (page != null) {
            if (page.tabs.isNotEmpty()) tabs = page.tabs
            // Keep only real titles (movies/series) in the numbered list; music/people stay out.
            val all = page.subjects
            results = when (tab) {
                "All", "MovieTV", "Movie", "TV" -> all.filter { it.type == 1 || it.type == 2 }
                else -> all
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextMain)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface1)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Search movies, series, anime…", color = TextDim, fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 14.sp),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitted = query }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Search",
                color = Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { submitted = query }.padding(4.dp),
            )
        }

        if (!searched) {
            // suggestions, like the stock app's "Everyone is searching"
            if (suggestions.isNotEmpty()) {
                Text(
                    "🔥 Everyone is searching",
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 10.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(suggestions.size) { i ->
                        val s = suggestions[i]
                        Text(
                            text = s,
                            color = TextMain,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface1)
                                .clickable {
                                    query = s
                                    submitted = s
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Type a name for instant, ranked results.",
                    color = TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        } else {
            // Flatten "Movie/Series" into its Movie and TV sub-tabs, like the stock app.
            val displayTabs = if (tabs.isEmpty()) listOf(SearchTab("All", "All"))
            else tabs.flatMap { t -> if (t.subs.isEmpty()) listOf(t) else t.subs }
            TabStrip(
                tabs = displayTabs,
                selected = tab,
                onSelect = { tab = it },
            )
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                }
            }
            if (!loading && results.isEmpty()) {
                MessageBox("No results for \"$submitted\". Check the spelling or try the Hindi/English tag.")
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results.size) { i ->
                    ResultRow(rank = i + 1, item = results[i], onClick = { onOpen(results[i]) })
                }
            }
        }
    }
}

@Composable
private fun TabStrip(tabs: List<SearchTab>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        items(tabs.size) { i ->
            val t = tabs[i]
            val on = t.id == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = t.name,
                    color = if (on) TextMain else TextDim,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable { onSelect(t.id) }
                        .padding(vertical = 6.dp),
                )
                Box(
                    Modifier
                        .height(3.dp)
                        .width(if (on) 22.dp else 0.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (on) Accent else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun ResultRow(rank: Int, item: Subject, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (rank <= 3) Accent else Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text("$rank", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        AsyncImage(
            model = item.cover,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(56.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface2),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = TextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.rating.isNotBlank() && item.rating != "0") {
                    Text("★ ${item.rating}", color = Color(0xFFFFC107), fontSize = 11.sp)
                    Text("  |  ", color = TextDim, fontSize = 11.sp)
                }
                val meta = listOf(
                    item.year,
                    item.country.ifBlank { if (item.isSeries) "Series" else "Movie" },
                    item.genre.substringBefore(","),
                ).filter { it.isNotBlank() }.joinToString("  |  ")
                Text(meta, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(if (item.isSeries) "SERIES" else "MOVIE", color = TextDim, fontSize = 9.sp)
    }
}
