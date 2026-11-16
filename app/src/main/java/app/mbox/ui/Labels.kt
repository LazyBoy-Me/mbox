package app.mbox.ui

import app.mbox.Dub

fun langLabel(d: Dub): String {
    if (d.original || d.name.startsWith("Original")) return "Original"
    return when (d.code.lowercase()) {
        "en" -> "English"
        "hi" -> "Hindi"
        "bn" -> "বাংলা"
        "te" -> "Telugu"
        "ta" -> "Tamil"
        "ml" -> "Malayalam"
        "kn" -> "Kannada"
        "mr" -> "Marathi"
        "gu" -> "Gujarati"
        "pa" -> "Punjabi"
        "ur" -> "Urdu"
        "es" -> "Español"
        "esla" -> "Español (LatAm)"
        "pt" -> "Português"
        "ptbr" -> "Português (BR)"
        "id" -> "Indonesia"
        "th" -> "ไทย"
        "vi" -> "Tiếng Việt"
        "ar" -> "العربية"
        "ko" -> "한국어"
        "ja" -> "日本語"
        "zh" -> "中文"
        else -> d.name.removeSuffix(" dub").ifBlank { d.code.uppercase() }
    }
}

fun codecLabel(codec: String): String = when (codec.lowercase()) {
    "hevc", "h265", "hvc1" -> "HEVC"
    "h264", "avc", "avc1" -> "H.264"
    "av1" -> "AV1"
    else -> codec.uppercase().ifBlank { "MP4" }
}

fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
