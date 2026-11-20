package app.mbox

data class Subject(
    val id: String = "",
    val title: String = "",
    val type: Int = 1,          // 1 = movie, 2 = series
    val cover: String = "",
    val year: String = "",
    val duration: String = "",
    val genre: String = "",
    val rating: String = "",
    val country: String = "",
    val badge: String = "",     // "Hindi", "CAM", ...
    val desc: String = "",
    // extra metadata the detail endpoint returns
    val aka: String = "",
    val language: String = "",
    val viewers: String = "",
    val contentRating: String = "",
    val wantCount: Int = 0,
    val seenCount: Int = 0,
    val seasonCount: Int = 0,
    val staff: List<Staff> = emptyList(),
    val subtitleLangs: List<String> = emptyList(),
    val trailer: String = "",
    val still: String = "",
) {
    val isSeries: Boolean get() = type == 2
}

data class Staff(
    val id: String = "",
    val name: String = "",
    val character: String = "",   // "Director" / role name
    val avatar: String = "",
)

/** A subtitle track (`subject-api/get-ext-captions`). */
data class Caption(
    val id: String = "",
    val lang: String = "",
    val name: String = "",
    val url: String = "",
) {
    val mime: String
        get() = when {
            url.endsWith(".srt", true) -> "application/x-subrip"
            url.endsWith(".vtt", true) -> "text/vtt"
            url.endsWith(".ass", true) || url.endsWith(".ssa", true) -> "text/x-ssa"
            else -> "application/x-subrip"
        }
}

data class HotSection(val name: String, val items: List<Subject>)

data class Dub(
    val id: String,
    val name: String,
    val code: String,
    val original: Boolean,
)

data class Season(val se: Int, val maxEp: Int)

/** Everything the player needs; it resolves qualities/audio itself. */
data class PlaySpec(
    val baseId: String,        // original subject id (used to list audio tracks)
    val title: String,
    val series: Boolean = false,
    val se: Int = 0,
    val ep: Int = 0,
    val dubId: String = "",    // audio subject id to start with ("" = original)
    val maxEp: Int = 0,        // last episode of the current season (for "next episode")
    /** Play a downloaded file straight from disk instead of the network. */
    val localPath: String = "",
)

data class SniffInfo(
    val url: String,
    val name: String,
    val duration: Int,
    val size: Long,
) {
    /** Backend substitutes a short "Upgrade Guide" promo clip when it refuses to serve a title. */
    val looksLikePlaceholder: Boolean
        get() = name.equals("Upgrade Guide", ignoreCase = true) || duration in 1..60
}

data class StreamRes(
    val resolution: Int,
    val codec: String,
    val size: Long,
    val duration: Int,
    val name: String,
    val sourceUrl: String,
    /** Direct CDN url from the web BFF. */
    val url: String = "",
    /** Referer the CDN requires (`https://movie-box.co/movies/<slug>`). */
    val referer: String = "",
    /** resource/v2 identifiers, needed by the captions endpoint. */
    val resourceId: String = "",
    val postId: String = "",
)

/** One search tab reported by `subject-api/search/v2` (may carry sub-tabs like Movie/TV). */
data class SearchTab(
    val id: String,
    val name: String,
    val subs: List<SearchTab> = emptyList(),
)

/** A result block: either a ranked subject list or a people/video strip. */
data class SearchGroup(val title: String, val items: List<Subject>)

data class SearchPage(
    val tabs: List<SearchTab> = emptyList(),
    val groups: List<SearchGroup> = emptyList(),
) {
    val subjects: List<Subject> get() = groups.flatMap { it.items }
}

/** What the search page shows before a query: hot sections + "everyone is searching" words. */
data class SearchRank(
    val sections: List<HotSection> = emptyList(),
    val words: List<String> = emptyList(),
)

/** Filter sheet options from `subject-api/filter-items`. */
data class FilterOptions(
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val years: List<String> = emptyList(),
    val sorts: List<String> = emptyList(),
)

/** A ranking board ("TOP Movies", "Bollywood", …) from `tab/ranking-list`. */
data class RankBoard(val id: String, val name: String)

/** One entry of the on-device watch history. */
data class HistoryEntry(
    val id: String,
    val title: String,
    val cover: String,
    val type: Int,
    val se: Int,
    val ep: Int,
    val position: Long,
    val duration: Long,
    val updated: Long,
) {
    val progress: Float
        get() = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
}
