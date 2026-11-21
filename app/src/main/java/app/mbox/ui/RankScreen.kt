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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mbox.Api
import app.mbox.R
import app.mbox.RankBoard
import app.mbox.Subject

/**
 * Ranking boards (`tab/ranking-list`): the stock app's "TOP Movies", "Bollywood", … chips over a
 * ranked poster grid. Reachable from the home "Trending Now → Rankings" link.
 */
@Composable
fun RankScreen(onBack: () -> Unit, onOpen: (Subject) -> Unit) {
    var boards by remember { mutableStateOf(Api.cachedRankBoards().orEmpty()) }
    var board by remember { mutableStateOf(boards.firstOrNull()) }
    var items by remember {
        mutableStateOf(board?.let { Api.cachedRankSubjects(it.id).orEmpty() }.orEmpty())
    }
    var loading by remember { mutableStateOf(boards.isEmpty()) }

    LaunchedEffect(Unit) {
        if (boards.isEmpty()) {
            val list = runCatching { Api.rankBoards() }.getOrDefault(emptyList())
            boards = list
            board = list.firstOrNull()
        }
        loading = false
    }

    LaunchedEffect(board?.id) {
        val b = board ?: return@LaunchedEffect
        val cached = Api.cachedRankSubjects(b.id)
        if (cached != null) {
            items = cached
            loading = false
            return@LaunchedEffect
        }
        loading = items.isEmpty()
        items = runCatching { Api.rankSubjects(b.id) }.getOrDefault(emptyList())
        loading = false
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
                "Rankings",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (board != null) {
                Text(board!!.name, color = TextMute, fontSize = 11.sp)
            }
        }

        if (boards.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(boards.size) { i ->
                    val b = boards[i]
                    val active = b.id == board?.id
                    Text(
                        b.name,
                        color = if (active) Color.Black else TextDim,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (active) Accent else Surface2)
                            .clickable { board = b }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        when {
            loading -> LoadingBox()
            boards.isEmpty() -> MessageBox("Ranking boards are unavailable right now.")
            items.isEmpty() -> MessageBox("This board has no titles yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items.size) { i ->
                    PosterCard(
                        subject = items[i],
                        modifier = Modifier.fillMaxWidth(),
                        showRank = i + 1,
                        onClick = { onOpen(items[i]) },
                    )
                }
            }
        }
    }
}
