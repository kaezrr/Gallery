package org.fossify.gallery.video

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@UnstableApi
fun Context.getMedia3VideoDurationSeconds(path: String): Int {
    val retriever = MetadataRetriever.Builder(applicationContext, MediaItem.fromUri(toMediaUri(path))).build()
    return try {
        val durationUs = retriever.retrieveDurationUs().get(15, TimeUnit.SECONDS)
        if (durationUs <= 0L) 0 else (durationUs / 1_000_000.0).roundToInt()
    } catch (exception: Exception) {
        val cause = (exception as? ExecutionException)?.cause
        if (cause is Exception) throw cause else throw exception
    } finally {
        retriever.close()
    }
}
