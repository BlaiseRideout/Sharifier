package com.blaiserideout.sharifier

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.LruCache
import android.util.Size
import com.blaiserideout.sharifier.SingletonState.db
import java.io.File

class ThumbnailManager {
    private var thumbCache: LruCache<String, Bitmap>

    init {
        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()

        // Use 1/8th of the available memory for this memory cache.
        val cacheSize = maxMemory / 4

        thumbCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return bitmap.byteCount / 1024
            }
        }
    }

    private fun storeThumb(file: FileItem, thumb: Bitmap) {
        thumbCache.put(file.path, thumb)
        file.fileId?.let { fileId -> db.thumbsDao().insert(Thumbnail(fileId, thumb)) }
    }

    // Callback is called with resulting thumbnail bitmap if the operation wasn't canceled
    fun getThumb(
        file: FileItem,
        size: Size,
        canceled: CancellationSignal,
        cb: (bitmap: Bitmap) -> Unit
    ) {
        // Check in memory cache
        thumbCache.get(file.path)?.let(cb) ?:
        // Then check in database cache
        file.fileId?.let { fileId ->
            if (!canceled.isCanceled)
                db.thumbsDao().getThumbnailFor(fileId)?.let {
                    storeThumb(file, it.thumbnail)
                    cb(it.thumbnail)
                }
        } ?:
        // Then generate thumb
        run {
            // Otherwise, generate the thumb on a separate thread
            try {
                (if (Util.matchesMimeType("image/*", file.path))
                    ThumbnailUtils.createImageThumbnail(
                        File(file.path),
                        size,
                        canceled
                    )
                else if (Util.matchesMimeType("video/*", file.path))
                    ThumbnailUtils.createVideoThumbnail(
                        File(file.path),
                        size,
                        canceled
                    )
                else null)?.let { thumb: Bitmap ->
                    storeThumb(file, thumb)
                    cb(thumb)
                }
            } catch (e: OperationCanceledException) {
                // Scrolling quickly will generate a lot of canceled operations
                // No need to log
            } catch (e: Exception) {
                Util.warn("Failed to generate thumbnail for \"${file.path}\": $e")
            }
        }
    }

}