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
    val desc: String = "",
) {
    val isSeries: Boolean get() = type == 2
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
)
