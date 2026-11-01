package org.fossify.gallery.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.math.roundToInt

@UnstableApi
fun Context.getMedia3VideoDurationSeconds(path: String): Int {
    val sourceUri = Uri.parse(path).let { uri ->
        if (uri.scheme == null) Uri.fromFile(File(path)) else uri
    }
    val retriever = MetadataRetriever.Builder(applicationContext, MediaItem.fromUri(sourceUri)).build()
    return try {
        val durationUs = retriever.retrieveDurationUs().get()
        if (durationUs <= 0L) 0 else (durationUs / 1_000_000.0).roundToInt()
    } catch (exception: Exception) {
        val cause = (exception as? ExecutionException)?.cause
        if (cause is Exception) throw cause else throw exception
    } finally {
        retriever.close()
    }
}
