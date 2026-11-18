package app.mbox

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app downloader. Files land in the app's private external folder, so no storage permission is
 * needed and they keep playing offline from the Downloads tab.
 */
object Downloads {

    private const val RUNNING = "running"
    private const val DONE = "done"
    private const val FAILED = "failed"
    private const val CANCELED = "canceled"

    data class Task(
        val id: String,
        val title: String,
        val subtitle: String,
        val url: String,
        val referer: String,
        val path: String,
        val total: Long = 0L,
        val done: Long = 0L,
        val speed: Long = 0L,
        val status: String = "queued",
    ) {
        val progress: Float
            get() = when {
                status == DONE -> 1f
                total > 0L -> (done.toFloat() / total).coerceIn(0f, 1f)
                else -> 0f
            }

        val running: Boolean get() = status == RUNNING || status == "queued"
        val finished: Boolean get() = status == DONE

        /** Playful but honest speed labels, like the stock app shows. */
        val speedLabel: String
            get() = when {
                status != RUNNING -> ""
                speed >= 8_000_000 -> "Duper fast"
                speed >= 3_000_000 -> "Superfast"
                speed >= 800_000 -> "Fast"
                speed > 0 -> "Steady"
                else -> "Connecting…"
            }

        val etaLabel: String
            get() {
                if (status != RUNNING || speed <= 0L || total <= done) return ""
                val sec = (total - done) / speed
                return when {
                    sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m left"
                    sec >= 60 -> "${sec / 60}m ${sec % 60}s left"
                    else -> "${sec}s left"
                }
            }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    fun folder(ctx: Context): File =
        (ctx.getExternalFilesDir("Downloads") ?: File(ctx.filesDir, "Downloads")).apply { mkdirs() }

    fun find(id: String): Task? = _tasks.value.firstOrNull { it.id == id }

    private fun update(id: String, block: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    private fun safeName(text: String): String =
        text.replace(Regex("[^A-Za-z0-9 _.-]+"), " ").trim().replace(Regex("\\s+"), " ").take(70)

    /**
     * Starts (or restarts) a download. [key] makes it idempotent: the same episode+quality is never
     * queued twice.
     */
    fun start(
        ctx: Context,
        key: String,
        title: String,
        subtitle: String,
        stream: StreamRes,
    ) {
        val existing = find(key)
        if (existing != null) {
            if (existing.running || existing.finished) return
            // failed / canceled -> retry from scratch
        }
        val file = File(folder(ctx), safeName("$title $subtitle") + ".mp4")
        val task = Task(
            id = key,
            title = title,
            subtitle = subtitle,
            url = stream.url,
            referer = stream.referer,
            path = file.absolutePath,
            total = stream.size,
            status = RUNNING,
        )
        _tasks.update { list -> list.filterNot { it.id == key } + task }

        jobs[key] = scope.launch {
            var lastBytes = 0L
            var lastStamp = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", Api.WEB_UA)
                    .header("Referer", stream.referer)
                    .header("Accept", "*/*")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        update(key) { it.copy(status = FAILED) }
                        return@launch
                    }
                    val body = resp.body ?: run {
                        update(key) { it.copy(status = FAILED) }
                        return@launch
                    }
                    val total = body.contentLength().takeIf { it > 0 } ?: stream.size
                    update(key) { it.copy(total = total, done = 0L) }
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(128 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastStamp >= 500) {
                                    val speed = ((done - lastBytes) * 1000L / (now - lastStamp))
                                    lastBytes = done
                                    lastStamp = now
                                    update(key) { it.copy(done = done, speed = speed) }
                                }
                            }
                        }
                    }
                }
                update(key) { it.copy(done = it.total, speed = 0L, status = DONE) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    update(key) { it.copy(status = CANCELED, speed = 0L) }
                    throw e
                }
                update(key) { it.copy(status = FAILED, speed = 0L) }
            } finally {
                jobs.remove(key)
            }
        }
    }

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        update(key) { it.copy(status = CANCELED, speed = 0L) }
    }

    fun remove(ctx: Context, key: String) {
        cancel(key)
        find(key)?.let { runCatching { File(it.path).delete() } }
        _tasks.update { list -> list.filterNot { it.id == key } }
    }

    fun totalOnDisk(): Long = _tasks.value.filter { it.finished }.sumOf { it.done }
}
