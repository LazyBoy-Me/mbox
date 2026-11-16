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

data class StreamRes(
    val resolution: Int,
    val codec: String,
    val size: Long,
    val duration: Int,
    val name: String,
    val sourceUrl: String,
)
