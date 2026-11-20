package app.mbox.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.mbox.R
import app.mbox.Subject
import coil.compose.AsyncImage

// ------------------------------------------------------------------ home chrome

@Composable
fun MBoxLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_home_mb_logo),
        contentDescription = "MovieBox",
        modifier = modifier.height(26.dp),
    )
}

/** The stock home header: logo · search pill · download shortcut. */
@Composable
fun TopBar(onSearch: () -> Unit, onDownloads: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MBoxLogo()
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xB31F2126))
                .clickable(onClick = onSearch)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_search_fit_dark),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "Search movies, TV shows",
                color = TextMute,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Search", color = AccentSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier.size(38.dp).clickable(onClick = onDownloads),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_movie_download),
                contentDescription = "Downloads",
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

/** Genre chips with the brand underline the stock app draws under the active tab. */
@Composable
fun GenreStrip(
    items: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onBrowse: (() -> Unit)? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onBrowse != null) {
            item {
                Row(
                    Modifier
                        .clickable(onClick = onBrowse)
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Browse", color = AccentSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Image(
                        painter = painterResource(R.drawable.ic_arrow_right_white_small),
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        items(items.size) { i ->
            val name = items[i]
            val active = name == selected
            Column(
                Modifier
                    .clickable { onSelect(name) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name,
                    color = if (active) TextMain else TextMute,
                    fontSize = if (active) 15.sp else 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(if (active) 16.dp else 0.dp)
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) Accent else Color.Transparent),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ posters

@Composable
fun PosterCard(
    subject: Subject,
    modifier: Modifier = Modifier.width(108.dp),
    showRank: Int = 0,
    onClick: () -> Unit,
) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(4.dp))
                .background(Surface2),
        ) {
            AsyncImage(
                model = subject.cover,
                contentDescription = subject.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color(0x00000000), Color(0x99000000)))
                    )
            )
            if (subject.badge.isNotBlank()) {
                Text(
                    subject.badge,
                    color = Color.White,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (showRank > 0) {
                Text(
                    "$showRank",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (showRank <= 3) Accent else Color(0x99000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (subject.rating.isNotBlank() && subject.rating != "0" && subject.rating != "null") {
                Text(
                    "★ ${subject.rating}",
                    color = Score,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = subject.title,
            color = TextMain,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Three posters per row, exactly like the stock grid. */
@Composable
fun PosterGrid(items: List<Subject>, onClick: (Subject) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    PosterCard(
                        subject = s,
                        modifier = Modifier.weight(1f),
                        onClick = { onClick(s) },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun HorizontalPosterRow(
    list: List<Subject>,
    onClick: (Subject) -> Unit,
    showRank: Boolean = false,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list.size) { index ->
            PosterCard(
                list[index],
                modifier = Modifier.width(if (index < 3 && showRank) 116.dp else 100.dp),
                showRank = if (showRank) index + 1 else 0,
                onClick = { onClick(list[index]) },
            )
        }
    }
}

// ------------------------------------------------------------------ text bits

@Composable
fun SectionHeader(title: String, trailing: String = "", onTrailing: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = TextMain,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (trailing.isNotEmpty()) {
            Row(
                Modifier.then(if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(trailing, color = TextMute, fontSize = 12.sp)
                Image(
                    painter = painterResource(R.drawable.ic_arrow_right_white_small),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun MessageBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextMute, fontSize = 13.sp)
    }
}

/** Small pill with the stock app's translucent surface, used for actions and metadata. */
@Composable
fun SurfaceChip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = TextDim,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Surface2)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
fun CircleBack(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(R.drawable.ic_arrow_back_black_24),
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}
