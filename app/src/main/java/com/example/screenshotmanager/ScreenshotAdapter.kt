package com.example.screenshotmanager

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Grid of screenshots. Tapping opens one; a long press selects it and, while the finger stays
 * down, dragging extends the selection (see [DragSelectHelper]). While something is selected,
 * taps toggle selection instead of opening.
 */
class ScreenshotAdapter(
    private val contentResolver: ContentResolver,
    private val onOpen: (Screenshot) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<ScreenshotAdapter.Holder>() {

    private var items: List<Screenshot> = emptyList()
    private val selectedIds = HashSet<Long>()

    private var dragStart = RecyclerView.NO_POSITION
    private var dragSelects = true
    private var selectionBeforeDrag: Set<Long> = emptySet()

    private val loader = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnails = object : LruCache<Long, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    val all: List<Screenshot> get() = items
    val selected: List<Screenshot> get() = items.filter { it.id in selectedIds }
    val isDragging: Boolean get() = dragStart != RecyclerView.NO_POSITION

    fun submit(screenshots: List<Screenshot>) {
        items = screenshots
        selectedIds.retainAll(screenshots.map { it.id }.toSet())
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun toggleSelectAll() {
        if (selectedIds.size == items.size) selectedIds.clear() else selectedIds += items.map { it.id }
        selectionChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        selectionChanged()
    }

    fun selectWhere(predicate: (Screenshot) -> Boolean) {
        selectedIds.clear()
        selectedIds += items.filter(predicate).map { it.id }
        selectionChanged()
    }

    /** Starts a drag on [position]: the drag selects if that item was unselected, otherwise it deselects. */
    fun startDrag(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        dragStart = position
        dragSelects = items[position].id !in selectedIds
        selectionBeforeDrag = selectedIds.toSet()
        updateDrag(position)
    }

    fun updateDrag(position: Int) {
        if (!isDragging || position == RecyclerView.NO_POSITION) return
        selectedIds.clear()
        selectedIds += selectionBeforeDrag
        val range = items.subList(minOf(dragStart, position), maxOf(dragStart, position) + 1).map { it.id }
        if (dragSelects) selectedIds += range else selectedIds -= range.toSet()
        selectionChanged()
    }

    fun endDrag() {
        dragStart = RecyclerView.NO_POSITION
    }

    private fun selectionChanged() {
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
        onSelectionChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_screenshot, parent, false)
        if (parent.width > 0) view.layoutParams.height = parent.width / COLUMNS
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) holder.bind(items[position]) else holder.bindSelection(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.thumbnail)
        private val check: View = view.findViewById(R.id.check)
        private var boundId = -1L

        fun bind(screenshot: Screenshot) {
            boundId = screenshot.id
            bindSelection(screenshot)

            val cached = thumbnails.get(screenshot.id)
            image.setImageBitmap(cached)
            if (cached == null) loadThumbnail(screenshot)

            itemView.setOnClickListener {
                if (selectedIds.isEmpty()) onOpen(screenshot) else toggle(screenshot)
            }
            itemView.setOnLongClickListener {
                startDrag(bindingAdapterPosition)
                true
            }
        }

        fun bindSelection(screenshot: Screenshot) {
            val isSelected = screenshot.id in selectedIds
            val scale = if (isSelected) 0.85f else 1f
            image.scaleX = scale
            image.scaleY = scale
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
        }

        private fun toggle(screenshot: Screenshot) {
            if (!selectedIds.remove(screenshot.id)) selectedIds += screenshot.id
            notifyItemChanged(bindingAdapterPosition, SELECTION_PAYLOAD)
            onSelectionChanged()
        }

        private fun loadThumbnail(screenshot: Screenshot) {
            loader.execute {
                val bitmap = runCatching { contentResolver.loadThumbnail(screenshot.uri, Size(320, 320), null) }.getOrNull()
                    ?: return@execute
                mainHandler.post {
                    thumbnails.put(screenshot.id, bitmap)
                    if (boundId == screenshot.id) image.setImageBitmap(bitmap)
                }
            }
        }
    }

    companion object {
        const val COLUMNS = 3
        private val SELECTION_PAYLOAD = Any()
    }
}
