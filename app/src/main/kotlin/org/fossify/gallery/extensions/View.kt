package org.fossify.gallery.extensions

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import org.fossify.commons.extensions.toast
import org.fossify.commons.views.MySearchMenu
import kotlin.math.abs
import kotlin.math.pow

fun MySearchMenu.setScrollOnContent(enabled: Boolean) {
    val scrollableBar = binding.searchBarContainer
    val layoutParams = scrollableBar.layoutParams as? AppBarLayout.LayoutParams ?: return
    val baseHeight = layoutParams.height
    val baseBarPaddingLeft = scrollableBar.paddingLeft
    val baseBarPaddingTop = scrollableBar.paddingTop
    val baseBarPaddingRight = scrollableBar.paddingRight
    val baseBarPaddingBottom = scrollableBar.paddingBottom
    val baseParentPaddingLeft = paddingLeft
    val baseParentPaddingRight = paddingRight
    val baseParentPaddingBottom = paddingBottom
    val coordinator = parent as? CoordinatorLayout
    var topInset = 0
    val statusBarScrim = coordinator?.let {
        View(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(210, 0, 0, 0), Color.TRANSPARENT)
            ).apply {
                setColors(
                    intArrayOf(
                        Color.argb(210, 0, 0, 0),
                        Color.argb(190, 0, 0, 0),
                        Color.argb(145, 0, 0, 0),
                        Color.argb(80, 0, 0, 0),
                        Color.argb(30, 0, 0, 0),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.15f, 0.35f, 0.6f, 0.82f, 1f)
                )
            }
        }
    }

    fun updateScrimHeight() {
        val scrim = statusBarScrim ?: return
        val scrimLayoutParams = scrim.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        val barContentHeight = (scrollableBar.height - topInset).coerceAtLeast(0)
        val scrimHeight = topInset + (barContentHeight * 0.25f).toInt()
        if (scrimLayoutParams.height != scrimHeight) {
            scrimLayoutParams.height = scrimHeight
            scrim.layoutParams = scrimLayoutParams
        }
    }

    if (coordinator != null && statusBarScrim != null) {
        coordinator.addView(
            statusBarScrim,
            CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { gravity = Gravity.TOP }
        )
        scrollableBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateScrimHeight()
        }
        addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
            val scrollRange = appBar.totalScrollRange
            statusBarScrim.alpha = if (scrollRange > 0) {
                (abs(verticalOffset).toFloat() / scrollRange).coerceIn(0f, 1f).pow(0.7f)
            } else {
                0f
            }
        })
    }

    setApplyWindowInsets(false)
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
        topInset = insets.top
        scrollableBar.setPadding(
            baseBarPaddingLeft,
            baseBarPaddingTop + insets.top,
            baseBarPaddingRight,
            baseBarPaddingBottom
        )
        layoutParams.height = baseHeight + insets.top
        scrollableBar.layoutParams = layoutParams
        setPadding(
            baseParentPaddingLeft + insets.left,
            0,
            baseParentPaddingRight + insets.right,
            baseParentPaddingBottom
        )
        updateScrimHeight()
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)

    layoutParams.scrollFlags = if (enabled) {
        AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
            AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
            AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
    } else {
        AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
    }
    scrollableBar.layoutParams = layoutParams
}

fun View.sendFakeClick(x: Float, y: Float) {
    val uptime = SystemClock.uptimeMillis()
    val event = MotionEvent.obtain(uptime, uptime, MotionEvent.ACTION_DOWN, x, y, 0)
    dispatchTouchEvent(event)
    event.action = MotionEvent.ACTION_UP
    dispatchTouchEvent(event)
}

fun View.showContentDescriptionOnLongClick() {
    setOnLongClickListener {
        val contentDescription = contentDescription
        if (contentDescription != null) {
            context.toast(contentDescription.toString())
        }
        true
    }
}
