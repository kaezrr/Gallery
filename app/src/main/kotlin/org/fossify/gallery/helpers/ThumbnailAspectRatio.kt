package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.models.Medium
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Reads thumbnail dimensions away from the UI thread and keeps them for this process. */
object ThumbnailAspectRatio {
    private const val CACHE_SIZE = 512
    private const val MAX_PENDING_READS = 64
    private val cache = object : LruCache<String, Float>(CACHE_SIZE) {}
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val executor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_PENDING_READS)
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    fun get(path: String): Float? = synchronized(cache) { cache.get(path) }

    /** Populate ratios from MediaStore before the staggered grid is attached. */
    fun prime(context: Context, media: List<Medium>) {
        val videoPaths = media.asSequence().filter { it.isVideo() }.map { it.path }.toSet()
        val paths = media.asSequence()
            .filter { it.path.isNotEmpty() }
            .map { it.path }
            .distinct()
            .toList()
        if (paths.isEmpty()) return

        val projection = arrayOf(
            Images.Media.DATA,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            Images.Media.ORIENTATION
        )
        val uri = Files.getContentUri("external")

        // Keep below SQLite's usual bind-parameter limit.
        paths.chunked(500).forEach { pathBatch ->
            val selection = "${Images.Media.DATA} IN (${pathBatch.joinToString(",") { "?" }})"
            try {
                context.contentResolver.query(uri, projection, selection, pathBatch.toTypedArray(), null)?.use { cursor ->
                    val pathColumn = cursor.getColumnIndex(Images.Media.DATA)
                    val widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    val orientationColumn = cursor.getColumnIndex(Images.Media.ORIENTATION)
                    if (pathColumn < 0 || widthColumn < 0 || heightColumn < 0) return@use

                    while (cursor.moveToNext()) {
                        val path = cursor.getString(pathColumn) ?: continue
                        if (path in videoPaths && (orientationColumn < 0 || cursor.isNull(orientationColumn))) {
                            continue
                        }
                        val width = cursor.getInt(widthColumn)
                        val height = cursor.getInt(heightColumn)
                        val rotation = if (orientationColumn >= 0) cursor.getInt(orientationColumn) else 0
                        put(path, width, height, rotation)
                    }
                }
            } catch (_: Exception) {
                // Files without usable MediaStore dimensions are resolved by the background fallback.
            }
        }
    }

    private fun put(path: String, width: Int, height: Int, rotation: Int = 0) {
        if (width <= 0 || height <= 0) return
        val (displayWidth, displayHeight) = if (rotation % 180 != 0) height to width else width to height
        synchronized(cache) { cache.put(path, displayWidth.toFloat() / displayHeight) }
    }

    fun load(path: String, isVideo: Boolean, onLoaded: (Float) -> Unit) {
        if (get(path) != null || !pending.add(path)) return
        try {
            executor.execute {
                val ratio = readRatio(path, isVideo)
                synchronized(cache) { cache.put(path, ratio) }
                pending.remove(path)
                mainHandler.post { onLoaded(ratio) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A later bind can retry after the bounded queue drains.
            pending.remove(path)
        }
    }

    private fun readRatio(path: String, isVideo: Boolean): Float {
        return try {
            val dimensions = if (isVideo) readVideoDimensions(path) else readImageDimensions(path)
            if (dimensions.first > 0 && dimensions.second > 0) {
                dimensions.first.toFloat() / dimensions.second
            } else {
                1f
            }
        } catch (_: Exception) {
            1f
        }
    }

    private fun readImageDimensions(path: String): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        var width = options.outWidth
        var height = options.outHeight
        if (width > 0 && height > 0) {
            val orientation = ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            if (orientation in setOf(
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE
                )
            ) {
                val oldWidth = width
                width = height
                height = oldWidth
            }
        }
        return width to height
    }

    private fun readVideoDimensions(path: String): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) height to width else width to height
        } finally {
            retriever.release()
        }
    }
}
