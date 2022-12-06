package com.blaiserideout.sharifier

import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.blaiserideout.sharifier.SingletonState.activity
import com.blaiserideout.sharifier.SingletonState.contentResolver
import java.io.File

class Util {
    companion object {
        private const val LOG_TAG = "Sharifier"

        fun error(msg: String) {
            Log.e(LOG_TAG, msg)
        }

        fun warn(msg: String) {
            Log.w(LOG_TAG, msg)
        }

        // If called from the main thread, spawns the runnable on its own thread
        // Otherwise, runs on the calling thread
        fun runOnThread(cb: Runnable) {
            if(Looper.myLooper() == Looper.getMainLooper())
                Thread(cb).start()
            else
                cb.run()
        }

        private fun getPublicUri(path: String): Uri = activity.get()!!.let { activity ->
            FileProvider.getUriForFile(activity, "${activity.packageName}.provider", File(path))
        }

        fun getPublicUri(fileItem: FileItem): Uri = getPublicUri(fileItem.path)

        fun matchesMimeTypes(
            wantedMimeTypes: ArrayList<String>,
            path: String
        ): Boolean = wantedMimeTypes
            .map { wantedMimeType -> matchesMimeType(wantedMimeType, path) }
            .reduce { acc, next -> acc || next }

        fun matchesMimeType(
            wantedMimeType: String,
            path: String
        ): Boolean = if (wantedMimeType.isBlank() || wantedMimeType == "*/*") {
            true
        } else contentResolver.get()?.getType(getPublicUri(path))?.let { fileMimeType ->
            if (wantedMimeType.endsWith("/*"))
                fileMimeType.startsWith(wantedMimeType.substringBefore("/"), true)
            else
                fileMimeType.equals(wantedMimeType, true)
        } ?: false
    }
}