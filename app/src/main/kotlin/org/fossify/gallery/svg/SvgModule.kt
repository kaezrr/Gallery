package org.fossify.gallery.svg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.PictureDrawable

import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import org.fossify.gallery.video.Media3VideoFrame
import org.fossify.gallery.video.Media3VideoFrameModelLoader

import java.io.InputStream

@GlideModule
class SvgModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder()).append(InputStream::class.java, SVG::class.java, SvgDecoder())
        registry.append(
            Media3VideoFrame::class.java,
            Bitmap::class.java,
            Media3VideoFrameModelLoader.Factory(context)
        )
    }

    override fun isManifestParsingEnabled() = false
}
