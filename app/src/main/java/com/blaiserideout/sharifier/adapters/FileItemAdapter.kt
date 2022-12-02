package com.blaiserideout.sharifier.adapters

import android.app.ActionBar.LayoutParams
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.os.CancellationSignal
import android.view.Gravity
import android.widget.CheckBox
import android.widget.PopupWindow
import androidx.appcompat.widget.AppCompatImageButton
import androidx.recyclerview.widget.RecyclerView
import com.blaiserideout.sharifier.FileWithSentFriends
import com.blaiserideout.sharifier.R
import com.blaiserideout.sharifier.activities.NUM_COLUMNS
import java.util.concurrent.*
import com.blaiserideout.sharifier.SingletonState.files
import com.blaiserideout.sharifier.SingletonState.currentFriend
import com.blaiserideout.sharifier.ThumbnailManager
import kotlin.properties.Delegates

class FileItemAdapter(
    private val activity: Activity,
    private var itemSelectionChanged: () -> Unit
) : RecyclerView.Adapter<FileItemAdapter.ViewHolder>() {
    val selectedItems = mutableSetOf<Int>()

    val thumbnailManager = ThumbnailManager()

    // Only call from the main UI thread
    fun clearSelection() {
        val changedItems = selectedItems.toList()
        selectedItems.clear()
        changedItems.forEach(::notifyItemChanged)
        itemSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.file_item_view, parent, false)
        val gridSize = parent.measuredWidth / NUM_COLUMNS
        view.minimumHeight = gridSize
        view.minimumWidth = gridSize
        return ViewHolder(view, gridSize)
    }

    override fun onBindViewHolder(holder: FileItemAdapter.ViewHolder, position: Int) {
        if (files == null)
            return
        val fileDirItem = files!![position]
        holder.bind(fileDirItem)
        holder.itemView.tag = holder
    }

    open inner class ViewHolder(view: View, width: Int) : RecyclerView.ViewHolder(view) {
        // UI elements
        private val iconView: ImageView = view.findViewById(R.id.fileIcon)
        private val selectedCheckBox: CheckBox = view.findViewById(R.id.checkBox)
        private val sentStatusView: ImageView = view.findViewById(R.id.sentStatus)
        private val expandButton: AppCompatImageButton = view.findViewById(R.id.expandButton)

        private var item: FileWithSentFriends? = null

        init {
            itemView.setOnClickListener {
                selectedCheckBox.isChecked = !selectedCheckBox.isChecked
            }

            selectedCheckBox.setOnCheckedChangeListener { _, checked ->
                val wasSelected = selectedItems.contains(adapterPosition)
                if (checked != wasSelected) {
                    if (checked)
                        selectedItems.add(adapterPosition)
                    else
                        selectedItems.remove(adapterPosition)
                    itemSelectionChanged()
                }
            }
            expandButton.setOnClickListener(::expandImagePreview)
        }

        private fun expandImagePreview(view: View) {
            //
            val popupView = LayoutInflater.from(view.context).inflate(
                R.layout.image_preview_popup,
                null
            )
            val popupWindow = PopupWindow(
                popupView,
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                true
            )
            if (popupWindow.isShowing)
                popupWindow.dismiss()
            popupWindow.showAtLocation(activity.window.decorView.rootView, Gravity.CENTER, 0, 0)
            val previewImageView: ImageView = popupView.findViewById(R.id.preview_image_view)
            item?.file?.let { file ->
                // Get width and height
                val originalBitmap = BitmapFactory.decodeFile(file.path)

                val origWidth = originalBitmap.width
                val origHeight = originalBitmap.height
                val screenWidth = activity.window.decorView.rootView.measuredWidth
                val screenHeight = activity.window.decorView.rootView.measuredHeight

                // If the image is big enough we need to scale it
                if (origWidth > screenWidth * 2 || origHeight > screenHeight * 2) {
                    var scaledWidth by Delegates.notNull<Int>()
                    var scaledHeight by Delegates.notNull<Int>()

                    val imageAspect = origWidth.toFloat() / origHeight.toFloat()
                    val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
                    if (imageAspect > screenAspect) {
                        // Image is wider than the screen aspect, scale to width
                        scaledWidth = screenWidth
                        scaledHeight = (screenWidth / imageAspect).toInt()
                    } else {
                        // Image is taller than screen aspect, scale to height
                        scaledHeight = screenHeight
                        scaledWidth = (screenHeight * imageAspect).toInt()
                    }

                    previewImageView.setImageBitmap(
                        Bitmap.createScaledBitmap(
                            originalBitmap,
                            scaledWidth,
                            scaledHeight,
                            true
                        )
                    )
                } else {
                    previewImageView.setImageBitmap(originalBitmap)
                }

            }
            previewImageView.setOnClickListener {
                popupWindow.dismiss()
            }

        }

        fun bind(item_: FileWithSentFriends) {
            val imageChanged = item != item_
            this.item = item_

            selectedCheckBox.isChecked = selectedItems.contains(adapterPosition)

            if (imageChanged) {
                iconView.setImageResource(R.drawable.ic_placeholder_img)

                generateThumb()
            }

            currentFriend?.let {
                sentStatusView.setImageResource(
                    if (item!!.friends.contains(currentFriend))
                        android.R.drawable.presence_online
                    else
                        android.R.drawable.presence_invisible
                )
            }
        }

        // Thumbnail
        private var previousCanceled: CancellationSignal? = null
        private val size = Size(width, width)
        private val taskQueue: BlockingQueue<Runnable> = LinkedBlockingQueue()
        private val NUMBER_OF_CORES = Runtime.getRuntime().availableProcessors()

        // Each holder manages its own thread pool so we can target only one
        // thumbnail thread per holder
        private val threadPool = ThreadPoolExecutor(
            NUMBER_OF_CORES,
            NUMBER_OF_CORES * 2,
            100,
            TimeUnit.MILLISECONDS,
            taskQueue
        )

        private fun generateThumb() {
            taskQueue.clear()

            if (previousCanceled != null) {
                // Cancel previous thumb generation processes for this holder
                previousCanceled?.cancel()
            }

            val canceled = CancellationSignal()
            previousCanceled = canceled

            item?.file?.let { file ->
                threadPool.submit {
                    thumbnailManager.getThumb(
                        file, size, canceled
                    ) { thumb ->
                        if (!canceled.isCanceled)
                            activity.runOnUiThread { iconView.setImageBitmap(thumb) }
                    }
                }

            }
        }
    }

    override fun getItemCount(): Int = files?.size ?: 0
}