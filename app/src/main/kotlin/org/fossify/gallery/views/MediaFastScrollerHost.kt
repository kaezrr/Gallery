package org.fossify.gallery.views

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.gallery.R

/** Keeps the library fast scroller out of staggered mode; that library assumes a LinearLayoutManager. */
class MediaFastScrollerHost(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private lateinit var recyclerView: RecyclerView
    private var fastScroller: RecyclerViewFastScroller? = null
    private var scrollVertically = true
    private var color: Int? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        recyclerView = findViewById(R.id.media_grid)
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return if (::recyclerView.isInitialized) {
            recyclerView.canScrollVertically(direction)
        } else {
            super.canScrollVertically(direction)
        }
    }

    fun setStaggeredGridActive(active: Boolean) {
        if (active) {
            unwrapFastScroller()
        } else {
            wrapWithFastScrollerIfReady()
        }
    }

    fun updateColors(color: Int) {
        this.color = color
        fastScroller?.updateColors(color)
    }

    fun setScrollVertically(vertical: Boolean) {
        scrollVertically = vertical
        fastScroller?.setScrollVertically(vertical)
    }

    private fun wrapWithFastScrollerIfReady() {
        if (!::recyclerView.isInitialized || recyclerView.adapter == null || fastScroller != null) return
        if (recyclerView.parent === this) removeView(recyclerView)

        val scroller = RecyclerViewFastScroller(context)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scroller.addView(
            recyclerView,
            0,
            RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        scroller.setScrollVertically(scrollVertically)
        color?.let(scroller::updateColors)
        scroller.attachFastScrollerToRecyclerView(recyclerView)
        fastScroller = scroller
    }

    private fun unwrapFastScroller() {
        val scroller = fastScroller ?: return
        scroller.detachFastScrollerFromRecyclerView()
        scroller.removeView(recyclerView)
        removeView(scroller)
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fastScroller = null
    }
}
