package org.fossify.gallery.video

import android.content.Context
import android.net.Uri
import org.fossify.commons.extensions.getOTGPublicPath
import org.fossify.commons.extensions.isPathOnOTG
import java.io.File

/** Resolves the app's stored paths, including SAF-backed OTG paths, to readable media URIs. */
fun Context.toMediaUri(path: String): Uri {
    val readablePath = if (isPathOnOTG(path)) path.getOTGPublicPath(this) else path
    val parsed = Uri.parse(readablePath)
    return if (parsed.scheme == null) Uri.fromFile(File(readablePath)) else parsed
}
