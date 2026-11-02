package org.fossify.gallery.helpers

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem

class GridSpacingItemDecoration(
    val spanCount: Int, val spacing: Int, val isScrollingHorizontally: Boolean, val addSideSpacing: Boolean,
    var items: ArrayList<ThumbnailItem>, val useGridPosition: Boolean, val isCircularScrolling: Boolean = false
) : RecyclerView.ItemDecoration() {

    override fun toString() = "spanCount: $spanCount, spacing: $spacing, isScrollingHorizontally: $isScrollingHorizontally, addSideSpacing: $addSideSpacing, " +
        "items: ${items.hashCode()}, useGridPosition: $useGridPosition, isCircularScrolling: $isCircularScrolling"

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (spacing <= 1) {
            return
        }

        val position = parent.getChildAdapterPosition(view)
        val mediaPosition = if (isCircularScrolling && items.isNotEmpty()) position % items.size else position
        if (parent.layoutManager is StaggeredGridLayoutManager && !isScrollingHorizontally) {
            if (items.getOrNull(mediaPosition) !is Medium) {
                outRect.top = spacing / 2
                outRect.bottom = spacing / 2
            }
            return
        }
        val medium = items.getOrNull(mediaPosition) as? Medium ?: return
        val gridPositionToUse = if (useGridPosition) medium.gridPosition else position
        val column = gridPositionToUse % spanCount

        if (isScrollingHorizontally) {
            if (addSideSpacing) {
                outRect.top = spacing - column * spacing / spanCount
                outRect.bottom = (column + 1) * spacing / spanCount
                outRect.right = spacing

                if (mediaPosition < spanCount) {
                    outRect.left = spacing
                }
            } else {
                outRect.top = column * spacing / spanCount
                outRect.bottom = spacing - (column + 1) * spacing / spanCount
                if (mediaPosition >= spanCount) {
                    outRect.left = spacing
                }
            }
        } else {
            if (addSideSpacing) {
                outRect.left = spacing - column * spacing / spanCount
                outRect.right = (column + 1) * spacing / spanCount
                outRect.bottom = spacing

                if (mediaPosition < spanCount && !useGridPosition) {
                    outRect.top = spacing
                }
            } else {
                outRect.left = column * spacing / spanCount
                outRect.right = spacing - (column + 1) * spacing / spanCount

                if (gridPositionToUse >= spanCount) {
                    outRect.top = spacing
                }
            }
        }
    }
}
