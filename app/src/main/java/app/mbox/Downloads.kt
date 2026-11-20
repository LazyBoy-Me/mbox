package app.mbox

import android.content.Context
import android.content.SharedPreferences
import android.os.StatFs
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
import org.json.JSONArray
import org.json.JSONObject
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
    private const val PAUSED = "paused"

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
    private val paused = HashSet<String>()

    // Starting 20 episode downloads at once starves playback and scrolling: keep three in flight.
    private const val MAX_ACTIVE = 3
    private val waiters = ArrayDeque<String>()
    private val waiterStreams = HashMap<String, StreamRes>()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    // The queue lives in prefs as well: Android kills the process while episodes download, and a
    // task that only exists in memory comes back as a stuck "running" row forever.
    private var prefs: SharedPreferences? = null
    private var lastPersist = 0L

    fun folder(ctx: Context): File =
        (ctx.getExternalFilesDir("Downloads") ?: File(ctx.filesDir, "Downloads")).apply { mkdirs() }

    fun find(id: String): Task? = _tasks.value.firstOrNull { it.id == id }

    private fun update(id: String, block: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
        persist()
    }

    private fun persist(force: Boolean = false) {
        val store = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 2500L) return
        lastPersist = now
        val arr = JSONArray()
        _tasks.value.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("subtitle", t.subtitle)
                    .put("url", t.url)
                    .put("referer", t.referer)
                    .put("path", t.path)
                    .put("total", t.total)
                    .put("done", t.done)
                    // A transfer that was interrupted by the process dying restarts as paused.
                    .put("status", if (t.status == RUNNING || t.status == "queued") PAUSED else t.status)
            )
        }
        store.edit().putString("tasks", arr.toString()).apply()
    }

    /** Called once from [MBoxApp]; rebuilds the queue from disk and marks interrupted rows paused. */
    fun restore(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("mbox_downloads", Context.MODE_PRIVATE)
        val raw = prefs?.getString("tasks", "").orEmpty()
        if (raw.isBlank()) return
        val restored = ArrayList<Task>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path")
                val file = File(path)
                val total = o.optLong("total")
                val done = o.optLong("done")
                val saved = o.optString("status")
                val status = when {
                    !file.exists() -> CANCELED
                    total > 0 && file.length() >= total -> DONE
                    saved == DONE -> DONE
                    saved == CANCELED -> CANCELED
                    else -> PAUSED
                }
                if (status == CANCELED) continue
                restored.add(
                    Task(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle"),
                        url = o.optString("url"),
                        referer = o.optString("referer"),
                        path = path,
                        total = total,
                        done = if (status == DONE) (total.takeIf { it > 0 } ?: file.length()) else file.length().coerceAtLeast(done),
                        status = status,
                    )
                )
            }
        }
        if (restored.isNotEmpty()) _tasks.value = restored
        persist(true)
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
        if (existing != null && (existing.status == RUNNING || existing.finished)) return
        prefs = prefs ?: ctx.applicationContext.getSharedPreferences("mbox_downloads", Context.MODE_PRIVATE)
        val file = File(folder(ctx), safeName("$title $subtitle") + ".mp4")
        // A paused/failed task continues from the bytes already on disk.
        val offset = if (existing != null && file.exists()) file.length() else 0L
        paused.remove(key)
        val task = Task(
            id = key,
            title = title,
            subtitle = subtitle,
            url = stream.url,
            referer = stream.referer,
            path = file.absolutePath,
            total = stream.size,
            done = offset,
            status = RUNNING,
        )
        _tasks.update { list -> list.filterNot { it.id == key } + task }

        if (jobs.size >= MAX_ACTIVE) {
            update(key) { it.copy(status = "queued") }
            if (!waiters.contains(key)) waiters.add(key)
            waiterStreams[key] = stream
            persist(true)
            return
        }

        jobs[key] = scope.launch {
            var lastBytes = 0L
            var lastStamp = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", Api.WEB_UA)
                    .header("Referer", stream.referer)
                    .header("Accept", "*/*")
                    .apply { if (offset > 0) header("Range", "bytes=$offset-") }
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
                    val total = body.contentLength().takeIf { it > 0 }?.plus(offset) ?: stream.size
                    update(key) { it.copy(total = total, done = offset) }
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(file, offset > 0).use { output ->
                            val buf = ByteArray(128 * 1024)
                            var done = offset
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
                persist(true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    val keep = paused.remove(key)
                    update(key) { it.copy(status = if (keep) PAUSED else CANCELED, speed = 0L) }
                    throw e
                }
                update(key) { it.copy(status = FAILED, speed = 0L) }
                persist(true)
            } finally {
                jobs.remove(key)
                pump(ctx)
            }
        }
    }

    /** Launch queued tasks as soon as a slot frees up. */
    private fun pump(ctx: Context) {
        while (jobs.size < MAX_ACTIVE) {
            val key = if (waiters.isEmpty()) return else waiters.removeFirst()
            val t = find(key) ?: continue
            val stream = waiterStreams.remove(key) ?: continue
            if (t.finished || t.status == CANCELED || t.status == PAUSED) continue
            start(ctx, key, t.title, t.subtitle, stream)
        }
    }

    /** Continue a paused or failed transfer from the bytes already on disk. */
    fun resume(ctx: Context, id: String) {
        val t = find(id) ?: return
        if (t.running || t.finished) return
        start(
            ctx,
            key = t.id,
            title = t.title,
            subtitle = t.subtitle,
            stream = StreamRes(
                resolution = 0,
                codec = "",
                size = t.total,
                duration = 0,
                name = "",
                sourceUrl = "",
                url = t.url,
                referer = t.referer,
            ),
        )
    }

    /** Stops the transfer but keeps the partial file so [start] can continue it. */
    fun pause(key: String) {
        val task = find(key) ?: return
        if (task.finished) return
        waiters.remove(key)
        waiterStreams.remove(key)
        paused.add(key)
        jobs[key]?.cancel()
        update(key) { it.copy(status = PAUSED, speed = 0L) }
        persist(true)
    }

    fun cancel(key: String) {
        waiters.remove(key)
        waiterStreams.remove(key)
        jobs.remove(key)?.cancel()
        update(key) { it.copy(status = CANCELED, speed = 0L) }
        persist(true)
    }

    fun remove(ctx: Context, key: String) {
        cancel(key)
        waiters.remove(key)
        waiterStreams.remove(key)
        find(key)?.let { runCatching { File(it.path).delete() } }
        _tasks.update { list -> list.filterNot { it.id == key } }
        persist(true)
    }

    /** Bytes actually used by finished and half-finished episodes. */
    fun folderSize(ctx: Context): Long {
        val root = folder(ctx)
        val files = root.listFiles() ?: return 0L
        return files.filter { it.isFile }.sumOf { it.length() }
    }

    fun freeBytes(ctx: Context): Long = runCatching {
        StatFs(folder(ctx).absolutePath).availableBytes
    }.getOrDefault(0L)

    fun totalOnDisk(): Long = _tasks.value.filter { it.finished }.sumOf { it.done }
}
