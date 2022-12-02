package com.blaiserideout.sharifier

import android.app.Activity
import android.content.ContentResolver
import java.lang.ref.WeakReference

object SingletonState {
    lateinit var db: FileDatabase

    var files: List<FileWithSentFriends>? = null

    var currentFriend: Friend? = null

    // For util functions
    lateinit var contentResolver: WeakReference<ContentResolver>
    lateinit var activity: WeakReference<Activity>
}