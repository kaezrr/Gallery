package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MediaAdapter
import org.fossify.gallery.models.Medium
import org.fossify.gallery.video.toMediaUri
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Coordinates visible video previews through a reusable, bounded Media3 player pool. */
@UnstableApi
class VideoThumbnailPlaybackCoordinator(
    context: Context,
    private val recyclerView: RecyclerView,
    private val adapter: MediaAdapter,
    private val isPlaybackEnabled: () -> Boolean,
    private val shouldCropVideo: () -> Boolean,
    private val maxPlayers: () -> Int
) {
    private companion object {
        const val ENTER_VISIBILITY = 0.60f
        const val EXIT_VISIBILITY = 0.35f
        const val VISIBILITY_BUCKET_SIZE = 0.05f
        const val CENTER_BUCKET_SIZE = 0.12f
        const val DISTRIBUTION_BUCKET_SIZE = 0.08f
        const val SCROLL_IDLE_DELAY_MS = 300L
    }

    private data class Cell(
        val path: String,
        val itemView: View,
        val textureView: TextureView,
        val thumbnail: ImageView,
        val playIconHolder: View,
        val visibleFraction: Float,
        val normalizedCenterX: Float,
        val normalizedCenterY: Float,
        val centerProximity: Float
    )

    private inner class PlayerSlot {
        var player: ExoPlayer? = null
        var path: String? = null
        var mediaId: String? = null
        var itemView: View? = null
        var textureView: TextureView? = null
        var thumbnail: ImageView? = null
        var playIconHolder: View? = null
        var videoSize: VideoSize? = null
        var surface: Surface? = null
        var surfaceListener: TextureView.SurfaceTextureListener? = null
        var playerListener: Player.Listener? = null

        fun ensurePlayer(): ExoPlayer {
            player?.let { return it }
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 3000, 250, 500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            return ExoPlayer.Builder(appContext)
                .setLoadControl(loadControl)
                .build()
                .also { created ->
                    created.repeatMode = Player.REPEAT_MODE_ONE
                    created.volume = 0f
                    created.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        false
                    )
                    player = created
                }
        }

        fun detachSurface() {
            surfaceListener?.let { listener ->
                textureView?.takeIf { it.surfaceTextureListener === listener }
                    ?.surfaceTextureListener = null
            }
            surfaceListener = null
            player?.setVideoSurface(null)
            surface?.release()
            surface = null
        }

        fun detachTarget() {
            detachSurface()
            textureView?.alpha = 0f
            thumbnail?.visibility = View.VISIBLE
            playIconHolder?.visibility = View.VISIBLE
            textureView?.setTransform(Matrix())
            itemView = null
            textureView = null
            thumbnail = null
            playIconHolder = null
            videoSize = null
        }

        fun clearAssignment() {
            player?.playWhenReady = false
            detachTarget()
            playerListener?.let { listener -> player?.removeListener(listener) }
            playerListener = null
            player?.clearMediaItems()
            path = null
            mediaId = null
        }

        fun release() {
            clearAssignment()
            player?.release()
            player = null
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = recyclerView.handler ?: Handler(Looper.getMainLooper())
    private val slots = ArrayList<PlayerSlot>()
    private val failedPaths = HashSet<String>()
    private var nextMediaId = 0L
    private var lifecyclePaused = false
    private var released = false

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                scheduleReconcile()
            } else {
                mainHandler.removeCallbacks(reconcileRunnable)
                slots.forEach { slot ->
                    slot.player?.playWhenReady = false
                }
            }
        }
    }

    private val childAttachListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = scheduleReconcile()

        override fun onChildViewDetachedFromWindow(view: View) = onGridCellDetached(view)
    }

    private val reconcileRunnable = Runnable { reconcile() }

    init {
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnChildAttachStateChangeListener(childAttachListener)
        scheduleReconcile()
    }

    fun onGridCellBound(itemView: View, path: String, isVideo: Boolean) {
        val slot = slots.firstOrNull { it.itemView === itemView }
        if (slot != null && (!isVideo || slot.path != path)) {
            slot.clearAssignment()
        }
        if (!isVideo) {
            itemView.findViewById<TextureView>(R.id.video_preview_surface)?.alpha = 0f
        }
        scheduleReconcile()
    }

    fun onGridCellAttached() = scheduleReconcile()

    fun onGridCellDetached(itemView: View) {
        slots.firstOrNull { it.itemView === itemView }?.let { slot ->
            slot.clearAssignment()
        }
    }

    fun scheduleReconcile() {
        if (released) return
        mainHandler.removeCallbacks(reconcileRunnable)
        if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            mainHandler.postDelayed(reconcileRunnable, SCROLL_IDLE_DELAY_MS)
        }
    }

    fun onHostPaused() {
        lifecyclePaused = true
        mainHandler.removeCallbacks(reconcileRunnable)
        slots.forEach { slot ->
            slot.clearAssignment()
        }
    }

    fun onHostResumed() {
        lifecyclePaused = false
        scheduleReconcile()
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(reconcileRunnable)
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnChildAttachStateChangeListener(childAttachListener)
        slots.forEach(PlayerSlot::release)
        slots.clear()
        failedPaths.clear()
    }

    private fun reconcile() {
        if (released || lifecyclePaused) return
        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        if (!isPlaybackEnabled()) {
            releasePlayers()
            return
        }

        val visibleCells = getVisibleVideoCells()
        val cellsByPath = visibleCells.groupBy { it.path }.mapValues { (_, samePathCells) ->
            samePathCells.maxWithOrNull(
                compareBy<Cell> { it.visibleFraction }
                    .thenBy { it.centerProximity }
                    .thenBy { it.normalizedCenterY }
                    .thenBy { it.normalizedCenterX }
            )!!
        }
        val visiblePaths = cellsByPath.keys
        failedPaths.retainAll(visiblePaths)
        val activePaths = slots.mapNotNullTo(HashSet()) { it.path }
        val candidates = cellsByPath.values.sortedBy { it.path }.filter { cell ->
            if (cell.path in failedPaths) return@filter false
            val threshold = if (cell.path in activePaths) EXIT_VISIBILITY else ENTER_VISIBILITY
            cell.visibleFraction >= threshold
        }

        val playerLimit = maxPlayers().coerceIn(2, 6)
        val selected = selectCandidates(candidates, activePaths, playerLimit)
        val selectedPaths = selected.mapTo(HashSet()) { it.path }

        slots.forEach { slot ->
            if (slot.path != null && slot.path !in selectedPaths) {
                slot.clearAssignment()
            }
        }
        trimIdleSlots(playerLimit)

        selected.forEach { cell ->
            val slot = slots.firstOrNull { it.path == cell.path } ?: getAvailableSlot(playerLimit) ?: return@forEach
            assign(slot, cell)
        }
    }

    private fun selectCandidates(candidates: List<Cell>, activePaths: Set<String>, limit: Int): List<Cell> {
        val remaining = candidates.toMutableList()
        val selected = ArrayList<Cell>(limit)
        while (selected.size < limit && remaining.isNotEmpty()) {
            val best = remaining.minWithOrNull { a, b -> compareCandidates(a, b, selected, activePaths) }!!
            selected.add(best)
            remaining.remove(best)
        }
        return selected
    }

    /** Compares by visibility first, then center proximity, spatial spread and assignment stability. */
    private fun compareCandidates(a: Cell, b: Cell, selected: List<Cell>, activePaths: Set<String>): Int {
        compareDescending(
            (a.visibleFraction / VISIBILITY_BUCKET_SIZE).roundToInt(),
            (b.visibleFraction / VISIBILITY_BUCKET_SIZE).roundToInt()
        ).takeIf { it != 0 }?.let { return it }

        compareDescending(
            (a.centerProximity / CENTER_BUCKET_SIZE).roundToInt(),
            (b.centerProximity / CENTER_BUCKET_SIZE).roundToInt()
        ).takeIf { it != 0 }?.let { return it }

        if (selected.isNotEmpty()) {
            compareDescending(
                (nearestSelectedDistance(a, selected) / DISTRIBUTION_BUCKET_SIZE).roundToInt(),
                (nearestSelectedDistance(b, selected) / DISTRIBUTION_BUCKET_SIZE).roundToInt()
            ).takeIf { it != 0 }?.let { return it }
        }

        val aIsActive = a.path in activePaths
        val bIsActive = b.path in activePaths
        if (aIsActive != bIsActive) return if (aIsActive) -1 else 1

        // Stable, deterministic final tie-breaker; child attachment order is never consulted.
        return a.path.compareTo(b.path)
    }

    private fun compareDescending(a: Int, b: Int) = b.compareTo(a)

    private fun nearestSelectedDistance(cell: Cell, selected: List<Cell>): Float = selected.minOf { other ->
        val x = cell.normalizedCenterX - other.normalizedCenterX
        val y = cell.normalizedCenterY - other.normalizedCenterY
        (hypot(x, y) / sqrt(2f)).coerceIn(0f, 1f)
    }

    private fun getVisibleVideoCells(): List<Cell> {
        val cells = ArrayList<Cell>()
        val visibleRect = Rect()
        val contentWidth = (recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight).coerceAtLeast(1)
        val contentHeight = (recyclerView.height - recyclerView.paddingTop - recyclerView.paddingBottom).coerceAtLeast(1)
        for (index in 0 until recyclerView.childCount) {
            val itemView = recyclerView.getChildAt(index)
            val position = recyclerView.getChildAdapterPosition(itemView)
            val medium = adapter.media.getOrNull(position) as? Medium ?: continue
            if (!medium.isVideo() || itemView.width <= 0 || itemView.height <= 0) continue
            if (!itemView.getLocalVisibleRect(visibleRect)) continue

            val totalArea = itemView.width.toLong() * itemView.height
            val visibleArea = visibleRect.width().toLong() * visibleRect.height()
            val fraction = (visibleArea.toFloat() / totalArea).coerceIn(0f, 1f)
            val centerX = (itemView.x + visibleRect.exactCenterX() - recyclerView.paddingLeft) / contentWidth
            val centerY = (itemView.y + visibleRect.exactCenterY() - recyclerView.paddingTop) / contentHeight
            val centeredX = (centerX - 0.5f) * 2f
            val centeredY = (centerY - 0.5f) * 2f
            val centerProximity = (1f - hypot(centeredX, centeredY) / sqrt(2f)).coerceIn(0f, 1f)
            val texture = itemView.findViewById<TextureView>(R.id.video_preview_surface) ?: continue
            val thumbnail = itemView.findViewById<ImageView>(R.id.medium_thumbnail) ?: continue
            val playIconHolder = itemView.findViewById<View>(R.id.play_icon_holder) ?: continue
            cells.add(
                Cell(
                    medium.path,
                    itemView,
                    texture,
                    thumbnail,
                    playIconHolder,
                    fraction,
                    centerX.coerceIn(0f, 1f),
                    centerY.coerceIn(0f, 1f),
                    centerProximity
                )
            )
        }
        return cells
    }

    private fun getAvailableSlot(limit: Int): PlayerSlot? {
        slots.firstOrNull { it.path == null }?.let { return it }
        if (slots.size >= limit) return null
        return PlayerSlot().also(slots::add)
    }

    private fun trimIdleSlots(limit: Int) {
        while (slots.size > limit) {
            val idleIndex = slots.indexOfLast { it.path == null }
            if (idleIndex < 0) return
            slots.removeAt(idleIndex).release()
        }
    }

    private fun assign(slot: PlayerSlot, cell: Cell) {
        val targetChanged = slot.itemView !== cell.itemView
        if (targetChanged) {
            slot.player?.playWhenReady = false
            slot.detachTarget()
            slot.itemView = cell.itemView
            slot.textureView = cell.textureView
            slot.thumbnail = cell.thumbnail
            slot.playIconHolder = cell.playIconHolder
        }

        val player = slot.ensurePlayer()
        val newMedia = slot.path != cell.path
        if (newMedia) {
            slot.clearPlayerListener()
            player.playWhenReady = false
            player.clearMediaItems()
            slot.path = cell.path
            slot.mediaId = "video-preview-${++nextMediaId}"
            cell.thumbnail.visibility = View.VISIBLE
            cell.playIconHolder.visibility = View.VISIBLE
            cell.textureView.alpha = 0f
            slot.videoSize = null
            cell.textureView.setTransform(Matrix())
            // TextureView stretches its surface buffer to the view bounds. Keep
            // the decoder in fit mode and apply crop/fit explicitly with a view matrix.
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            player.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(slot.mediaId!!)
                    .setUri(appContext.toMediaUri(cell.path))
                    .build()
            )
        }

        if (slot.surfaceListener == null || targetChanged) {
            attachSurface(slot, cell.textureView)
        }
        updateTextureTransform(slot)
        if (newMedia) player.prepare()
        player.playWhenReady = true
    }

    private fun attachSurface(slot: PlayerSlot, textureView: TextureView) {
        if (slot.textureView !== textureView) return
        if (slot.surfaceListener != null) return
        slot.detachSurface()
        registerPlayerListener(slot)
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                if (slot.textureView !== textureView || slot.player == null) return
                val surface = Surface(surfaceTexture)
                slot.surface = surface
                slot.player?.setVideoSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateTextureTransform(slot)
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                if (slot.textureView === textureView) {
                    slot.player?.setVideoSurface(null)
                    slot.surface?.release()
                    slot.surface = null
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
        slot.surfaceListener = listener
        textureView.surfaceTextureListener = listener
        textureView.surfaceTexture?.let { listener.onSurfaceTextureAvailable(it, textureView.width, textureView.height) }
    }

    private fun registerPlayerListener(slot: PlayerSlot) {
        slot.clearPlayerListener()
        val player = slot.player ?: return
        val expectedMediaId = slot.mediaId ?: return
        val expectedPath = slot.path ?: return
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (slot.mediaId == expectedMediaId && slot.path == expectedPath) {
                    slot.videoSize = videoSize
                    updateTextureTransform(slot)
                }
            }

            override fun onRenderedFirstFrame() {
                if (slot.mediaId == expectedMediaId && slot.path == expectedPath
                    && player.currentMediaItem?.mediaId == expectedMediaId
                    && player.playWhenReady && slot.surface != null && slot.itemView != null
                ) {
                    slot.thumbnail?.visibility = View.INVISIBLE
                    slot.playIconHolder?.visibility = View.GONE
                    slot.textureView?.alpha = 1f
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (slot.mediaId == expectedMediaId && slot.path == expectedPath) {
                    failedPaths.add(expectedPath)
                    slot.clearAssignment()
                    scheduleReconcile()
                }
            }
        }
        slot.playerListener = listener
        player.addListener(listener)
    }

    private fun PlayerSlot.clearPlayerListener() {
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
    }

    private fun showPoster(slot: PlayerSlot) {
        slot.textureView?.alpha = 0f
        slot.thumbnail?.visibility = View.VISIBLE
        slot.playIconHolder?.visibility = View.VISIBLE
    }

    private fun updateTextureTransform(slot: PlayerSlot) {
        val textureView = slot.textureView ?: return
        val videoSize = slot.videoSize ?: return
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoSize.width <= 0 || videoSize.height <= 0) return

        var videoWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        var videoHeight = videoSize.height.toFloat()
        if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
            val oldWidth = videoWidth
            videoWidth = videoHeight
            videoHeight = oldWidth
        }

        val viewAspect = viewWidth / viewHeight
        val videoAspect = videoWidth / videoHeight
        val crop = shouldCropVideo()
        val scaleX: Float
        val scaleY: Float
        if ((videoAspect > viewAspect) == crop) {
            scaleX = if (crop) videoAspect / viewAspect else 1f
            scaleY = if (crop) 1f else viewAspect / videoAspect
        } else {
            scaleX = if (crop) 1f else videoAspect / viewAspect
            scaleY = if (crop) viewAspect / videoAspect else 1f
        }

        textureView.setTransform(Matrix().apply {
            setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        })
    }

    private fun releasePlayers() {
        slots.forEach(PlayerSlot::release)
        slots.clear()
        failedPaths.clear()
    }

}
