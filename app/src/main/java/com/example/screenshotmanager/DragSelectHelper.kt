package com.example.screenshotmanager

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * Gallery-style drag selection: after a long press starts a drag in [adapter], moving the finger
 * selects every item between the first and the current one, scrolling when near the edges.
 */
class DragSelectHelper(
    private val recyclerView: RecyclerView,
    private val adapter: ScreenshotAdapter,
) : RecyclerView.SimpleOnItemTouchListener() {

    private var lastX = 0f
    private var lastY = 0f
    private var scrollSpeed = 0

    private val autoScroll = object : Runnable {
        override fun run() {
            if (scrollSpeed == 0 || !adapter.isDragging) return
            recyclerView.scrollBy(0, scrollSpeed)
            selectUnderFinger()
            recyclerView.postOnAnimation(this)
        }
    }

    init {
        recyclerView.addOnItemTouchListener(this)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!adapter.isDragging) return false
        return when (e.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stop()
                false
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                true
            }
            else -> false
        }
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> track(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
        }
    }

    private fun track(e: MotionEvent) {
        lastX = e.x
        lastY = e.y
        selectUnderFinger()
        updateAutoScroll()
    }

    private fun selectUnderFinger() {
        val child = recyclerView.findChildViewUnder(lastX, lastY) ?: return
        adapter.updateDrag(recyclerView.getChildAdapterPosition(child))
    }

    private fun updateAutoScroll() {
        val edge = recyclerView.height / 6f
        val speed = when {
            lastY < edge -> -(MAX_SCROLL * (edge - lastY) / edge).toInt().coerceAtLeast(MIN_SCROLL)
            lastY > recyclerView.height - edge ->
                (MAX_SCROLL * (lastY - (recyclerView.height - edge)) / edge).toInt().coerceAtLeast(MIN_SCROLL)
            else -> 0
        }
        val wasScrolling = scrollSpeed != 0
        scrollSpeed = speed
        if (!wasScrolling && speed != 0) recyclerView.postOnAnimation(autoScroll)
    }

    private fun stop() {
        scrollSpeed = 0
        recyclerView.removeCallbacks(autoScroll)
        adapter.endDrag()
    }

    private companion object {
        const val MIN_SCROLL = 4
        const val MAX_SCROLL = 40
    }
}
