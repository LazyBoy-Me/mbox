package app.mbox

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Process-wide setup.
 *
 * The stock app keeps one image pipeline for the whole process; Coil's default one is too
 * conservative for a poster wall (small disk cache, no crossfade, obeys the CDN's
 * `Cache-Control: no-cache`). This replaces it with a 250 MB on-disk cache so scrolling back
 * through rows is instant and costs no data.
 */
class MBoxApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Api.init(this)
        Downloads.restore(this)
        installCrashLog()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            // Poster CDNs answer with no-cache headers; ignoring them is what makes back-scroll free.
            .respectCacheHeaders(false)
            .crossfade(180)
            .build()

    /**
     * Saves the last fatal stack trace so Me → About can show it, then lets the system handle the
     * crash as usual. Compose has no error boundary, so this is the only reliable way to keep a
     * report without eating the crash dialog.
     */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val buffer = StringWriter()
                error.printStackTrace(PrintWriter(buffer))
                Store.lastCrash = buildString {
                    append(error.javaClass.simpleName).append(": ")
                    append(error.message.orEmpty().take(200))
                    append("\n\n")
                    append("thread: ").append(thread.name).append('\n')
                    append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
                    append(android.os.Build.MODEL).append(", Android ")
                    append(android.os.Build.VERSION.RELEASE).append(" (API ")
                    append(android.os.Build.VERSION.SDK_INT).append(")\n\n")
                    append(buffer.toString().lines().take(40).joinToString("\n"))
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
