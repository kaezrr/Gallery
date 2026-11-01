package org.fossify.gallery.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.inspector.frame.FrameExtractor
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.math.min

data class Media3VideoFrame(val path: String)

class Media3VideoFrameModelLoader(
    private val context: Context
) : ModelLoader<Media3VideoFrame, Bitmap> {
    override fun buildLoadData(
        model: Media3VideoFrame,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<Bitmap> {
        val shortSide = if (width > 0 && height > 0) {
            min(width, height).coerceAtMost(MAX_FRAME_SHORT_SIDE)
        } else {
            DEFAULT_FRAME_SHORT_SIDE
        }

        return ModelLoader.LoadData(
            ObjectKey(model.path),
            Media3VideoFrameFetcher(context, model.path, shortSide)
        )
    }

    override fun handles(model: Media3VideoFrame) = true

    class Factory(context: Context) : ModelLoaderFactory<Media3VideoFrame, Bitmap> {
        private val appContext = context.applicationContext

        override fun build(multiFactory: MultiModelLoaderFactory) =
            Media3VideoFrameModelLoader(appContext)

        override fun teardown() = Unit
    }

    private companion object {
        const val DEFAULT_FRAME_SHORT_SIDE = 480
        const val MAX_FRAME_SHORT_SIDE = 512
    }
}

@UnstableApi
private class Media3VideoFrameFetcher(
    private val context: Context,
    private val path: String,
    private val frameShortSide: Int
) : DataFetcher<Bitmap> {
    @Volatile
    private var cancelled = false

    @Volatile
    private var extractionFuture: com.google.common.util.concurrent.ListenableFuture<FrameExtractor.Frame>? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        if (cancelled) {
            return
        }

        var extractor: FrameExtractor? = null
        try {
            val sourceUri = Uri.parse(path).let { uri ->
                if (uri.scheme == null) Uri.fromFile(File(path)) else uri
            }

            val frameExtractor = FrameExtractor.Builder(context, MediaItem.fromUri(sourceUri))
                .setEffects(listOf(Presentation.createForShortSide(frameShortSide)))
                .build()
            extractor = frameExtractor

            val future = frameExtractor.getThumbnail()
            extractionFuture = future
            if (cancelled) {
                future.cancel(true)
            }

            val bitmap = future.get().bitmap
            if (!cancelled) {
                callback.onDataReady(bitmap)
            }
        } catch (exception: Exception) {
            if (!cancelled) {
                val cause = (exception as? ExecutionException)?.cause as? Exception ?: exception
                callback.onLoadFailed(cause)
            }
        } finally {
            extractionFuture = null
            extractor?.close()
        }
    }

    override fun cleanup() = Unit

    override fun cancel() {
        cancelled = true
        extractionFuture?.cancel(true)
    }

    override fun getDataClass() = Bitmap::class.java

    override fun getDataSource() = DataSource.LOCAL
}
