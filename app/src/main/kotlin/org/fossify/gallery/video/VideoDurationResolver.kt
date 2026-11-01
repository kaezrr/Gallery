package org.fossify.gallery.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Resolves missing durations for visible video rows without blocking media enumeration. */
object VideoDurationResolver {
    private const val CACHE_SIZE = 512
    private const val MAX_PENDING_READS = 64
    private val cache = object : LruCache<String, Int>(CACHE_SIZE) {}
    private val callbacks = HashMap<String, MutableList<(Int) -> Unit>>()
    private val executor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_PENDING_READS)
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    fun get(path: String): Int? = synchronized(cache) { cache.get(path) }

    fun load(context: Context, path: String, onLoaded: (Int) -> Unit) {
        var cachedDuration: Int? = null
        var shouldSchedule = false
        synchronized(callbacks) {
            cachedDuration = synchronized(cache) { cache.get(path) }
            if (cachedDuration == null) {
                val waitingCallbacks = callbacks[path]
                if (waitingCallbacks == null) {
                    callbacks[path] = mutableListOf(onLoaded)
                    shouldSchedule = true
                } else {
                    waitingCallbacks.add(onLoaded)
                }
            }
        }

        cachedDuration?.let { duration ->
            mainHandler.post { onLoaded(duration) }
            return
        }
        if (!shouldSchedule) return

        try {
            val appContext = context.applicationContext
            executor.execute {
                val duration = resolveDuration(appContext, path)
                synchronized(cache) { cache.put(path, duration) }
                val waitingCallbacks = synchronized(callbacks) {
                    callbacks.remove(path)?.toList().orEmpty()
                }
                mainHandler.post { waitingCallbacks.forEach { it(duration) } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(callbacks) { callbacks.remove(path) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun resolveDuration(context: Context, path: String): Int {
        val platformDuration = try {
            MediaMetadataRetriever().useRetriever { retriever ->
                retriever.setDataSource(context, context.toMediaUri(path))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let { (it / 1000.0).roundToInt() }
                    ?: 0
            }
        } catch (_: Exception) {
            0
        }
        if (platformDuration > 0) return platformDuration

        return try {
            context.getMedia3VideoDurationSeconds(path)
        } catch (_: Exception) {
            0
        }
    }

    private inline fun <T> MediaMetadataRetriever.useRetriever(block: (MediaMetadataRetriever) -> T): T {
        return try {
            block(this)
        } finally {
            release()
        }
    }
}
