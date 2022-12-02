package com.blaiserideout.sharifier.activities

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.ActionBar.LayoutParams
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.provider.MediaStore.Files.FileColumns
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.blaiserideout.sharifier.*
import com.blaiserideout.sharifier.adapters.FileItemAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.lang.ref.WeakReference
import java.time.Instant
import kotlin.collections.ArrayList
import com.blaiserideout.sharifier.SingletonState.files
import com.blaiserideout.sharifier.SingletonState.currentFriend
import com.blaiserideout.sharifier.SingletonState.db

const val RECENTS_CHUNK_SIZE = 500
const val NUM_COLUMNS = 4

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: FileItemAdapter
    private lateinit var layoutManager: GridLayoutManager

    // UI
    private lateinit var shareButton: FloatingActionButton
    private lateinit var recentsListView: RecyclerView
    private lateinit var refreshLayout: SwipeRefreshLayout
    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = Room.databaseBuilder(
            this,
            FileDatabase::class.java,
            "fileDatabase"
        ).build()

        SingletonState.contentResolver = WeakReference(contentResolver)
        SingletonState.activity = WeakReference(this)

        recentsListView = findViewById(R.id.recents_list)
        layoutManager = GridLayoutManager(applicationContext, NUM_COLUMNS)
        recentsListView.layoutManager = layoutManager
        adapter = FileItemAdapter(this, ::updateTitle)
        recentsListView.adapter = adapter

        shareButton = findViewById(R.id.share_button)
        shareButton.setOnClickListener { shareSelectedFiles() }
        shareButton.hide()

        refreshLayout = findViewById(R.id.swipe_container)
        refreshLayout.setOnRefreshListener(::refreshFiles)

        drawerLayout = findViewById(R.id.drawer_layout)

        initializeMenus()

        refreshFiles()
    }

    //region sharing

    private fun shareSelectedFiles() {
        val selectedFiles =
            ArrayList(adapter.selectedItems.map { Util.getPublicUri(files!![it].file) })

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*|video/*"

            putExtra(Intent.EXTRA_MIME_TYPES, supportedMimeTypes)

            putParcelableArrayListExtra(Intent.EXTRA_STREAM, selectedFiles)

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        ).forEach { packageIt ->
            selectedFiles.forEach {
                grantUriPermission(
                    packageIt.activityInfo.packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        startActivity(Intent.createChooser(intent, null))

        markSelectedFilesAsSent {
            adapter.clearSelection()
        }
    }

    private fun markSelectedFilesAsSent(cb: (() -> Unit)? = null) {
        currentFriend?.friendId?.let { currentFriendId ->
            adapter.selectedItems.forEach {
                val selectedFile = files!![it]
                if (!selectedFile.friends.contains(currentFriend)) {
                    selectedFile.friends.add(currentFriend!!)
                    selectedFile.file.fileId?.let { fileId ->
                        Thread {
                            db.filesDao().sentFile(
                                SentItemsCrossRef(
                                    fileId,
                                    currentFriendId
                                )
                            )
                        }.start()
                    }
                }
            }

            cb?.invoke()
            updateVisibleItems()
        }
    }

    private fun unsendSelectedFiles(cb: (() -> Unit)? = null) {
        currentFriend?.friendId?.let { currentFriendId ->
            adapter.selectedItems.forEach {
                val selectedFile = files!![it]
                selectedFile.friends.remove(currentFriend!!)
                selectedFile.file.fileId?.let { fileId ->
                    Thread {
                        db.filesDao().unsendFile(
                            SentItemsCrossRef(
                                fileId,
                                currentFriendId
                            )
                        )
                    }.start()
                }
            }

            cb?.invoke()
            updateVisibleItems()
        }
    }

    private fun updateTitle() {
        if (adapter.selectedItems.isNotEmpty()) {
            title = "${adapter.selectedItems.size} selected" +
                    if (currentFriend != null) " for ${currentFriend!!.name}"
                    else ""

            shareButton.show()
        } else {
            if (currentFriend != null)
                title = "For ${currentFriend!!.name}"
            else
                setTitle(R.string.app_name)
            shareButton.hide()
        }
    }

    //endregion

    //region menus

    private fun updateVisibleItems() {
        adapter.notifyItemRangeChanged(
            layoutManager.findFirstVisibleItemPosition(),
            layoutManager.findLastVisibleItemPosition()
        )
    }

    private fun initializeMenus() {
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_baseline_menu_24)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.app_name,
            R.string.app_name
        )
        drawerLayout.addDrawerListener(drawerToggle)
        //This is necessary to change the icon of the Drawer Toggle upon state change.
        drawerToggle.syncState()

        populateFriendList()
    }

    private fun switchFriend(friend: Friend) {
        currentFriend = friend
        updateVisibleItems()
        Thread {
            friend.friendId?.let { friendId ->
                db.filesDao().getLastSentFileForFriend(friendId)?.let { lastTimestamp ->
                    val lastSentItemPosition = files?.binarySearch {
                        (lastTimestamp.epochSecond - it.file.modified.epochSecond).toInt()
                    }
                    lastSentItemPosition?.let {
                        runOnUiThread {
                            recentsListView.scrollToPosition(it)
                        }
                    }
                }
            }
        }.start()
    }

    private fun populateFriendList() {
        Thread {
            val friends = db.friendsDao().getFriends()
            runOnUiThread {
                val drawer: NavigationView = findViewById(R.id.left_drawer)
                drawer.menu.let { contextMenu ->
                    contextMenu.removeGroup(R.id.friends_group)
                    for (friend in friends) {
                        val item =
                            contextMenu.add(
                                R.id.friends_group,
                                Menu.NONE,
                                Menu.NONE,
                                friend.name
                            )
                        item.setOnMenuItemClickListener {
                            switchFriend(friend)
                            drawerLayout.closeDrawer(drawer)
                            true
                        }
                    }
                }
                drawer.setNavigationItemSelectedListener { menuItem: MenuItem ->
                    when (menuItem.itemId) {
                        R.id.add_friend -> addFriend()
                    }
                    drawerLayout.closeDrawer(drawer)
                    true
                }
            }
        }.start()
    }

    private fun addFriend() {
        val popupView = layoutInflater.inflate(
            R.layout.add_friend_popup,
            null
        )
        val popupWindow = PopupWindow(
            popupView,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.showAtLocation(window.decorView.rootView, Gravity.CENTER, 0, 0)
        val friendNameView: EditText = popupView.findViewById(R.id.edit_friend_name)
        val button: Button = popupView.findViewById(R.id.add_friend_button)
        button.setOnClickListener {
            val newName = friendNameView.text.toString()
            if (newName.isNotBlank()) {
                Thread {
                    db.friendsDao().insert(Friend(newName))
                    populateFriendList()
                }.start()
                popupWindow.dismiss()
            }
        }
        friendNameView.requestFocus()

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.clear_selection -> adapter.clearSelection()
            R.id.unsend_selection -> unsendSelectedFiles()
            R.id.mark_selection_sent -> markSelectedFilesAsSent()
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.kebab_menu, menu)
        return true
    }

    //endregion


    @SuppressLint("NotifyDataSetChanged")
    private fun populateList() {
        // If we have the right number of files, don't need to repopulate list (probably)
        if (db.filesDao().getNumFiles() == files?.size)
            return

        val oldFiles = files
        files = db.filesDao().getFilesWithSentFriends()

        runOnUiThread {
            if (oldFiles != null &&
                (files!!.size < oldFiles.size || files!![0] != oldFiles[0] ||
                        files!![oldFiles.size - 1] != oldFiles[oldFiles.size - 1])
            ) {
                // Adding items anywhere but the end of the list invalidates indices;
                // need to regenerate the whole list view
                adapter.clearSelection()
                adapter.notifyDataSetChanged()
            } else {
                val previousLength = oldFiles?.size ?: 0
                adapter.notifyItemRangeInserted(
                    previousLength,
                    files!!.size - previousLength
                )
            }
        }
    }

    private fun refreshFiles() {
        refreshLayout.isRefreshing = true
        verifyPermissions {
            Thread {
                val latestFile = db.filesDao().getLatest() ?: Instant.EPOCH
                var i = 0
                while (true) {
                    val addedFiles = getRecents({ recents ->
                        db.filesDao().insertAll(recents)
                    }, i++, latestFile)
                    populateList()
                    if (!addedFiles)
                        break
                }
                populateList()

                runOnUiThread {
                    refreshLayout.isRefreshing = false
                }
            }.start()
        }
    }

    //region FS

    private val supportedMimeTypes = arrayListOf<String>().apply {
        add("image/*")
        add("video/*")
    }

    private fun getRecents(
        callback: (recents: ArrayList<FileItem>) -> Unit,
        chunkIndex: Int,
        timeCutoff: Instant = Instant.EPOCH
    ): Boolean {
        val listItems = arrayListOf<FileItem>()

        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            FileColumns.DATA, FileColumns.DATE_MODIFIED //, FileColumns.DISPLAY_NAME
        )

        try {
            val queryArgs = bundleOf(
                ContentResolver.QUERY_ARG_LIMIT to RECENTS_CHUNK_SIZE,
                ContentResolver.QUERY_ARG_OFFSET to (RECENTS_CHUNK_SIZE * chunkIndex),
                ContentResolver.QUERY_ARG_SORT_COLUMNS to arrayOf(FileColumns.DATE_MODIFIED),
                ContentResolver.QUERY_ARG_SQL_SELECTION to "${FileColumns.DATE_MODIFIED} > ${timeCutoff.epochSecond}",
                ContentResolver.QUERY_ARG_SORT_DIRECTION to ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )

            applicationContext?.contentResolver?.query(uri, projection, queryArgs, null)
                .use { cursor ->
                    if (cursor?.moveToFirst() == true) {
                        do {
                            val path =
                                cursor.getString(cursor.getColumnIndexOrThrow(FileColumns.DATA))
                            val file = File(path)

                            if (file.isDirectory)
                                continue

                            if (!file.isHidden && file.exists() &&
                                Util.matchesMimeTypes(supportedMimeTypes, path)
                            ) {
                                val modified =
                                    cursor.getLong(cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED))

                                listItems.add(FileItem(path, Instant.ofEpochSecond(modified)))
                            }
                        } while (cursor.moveToNext())
                    }
                }
        } catch (e: Exception) {
            Util.error("Failed to get recent files: $e")
            runOnUiThread {
                Toast.makeText(applicationContext, e.toString(), Toast.LENGTH_LONG).show()
            }
        }

        if (listItems.isEmpty())
            return false

        callback(listItems)

        return true
    }


    private fun verifyPermissions(callback: () -> Unit) {
        if (applicationContext.checkSelfPermission(permission.READ_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
            callback()
        } else {
            // Register the permissions callback, which handles the user's response to the
            // system permissions dialog. Save the return value, an instance of
            // ActivityResultLauncher. You can use either a val, as shown in this snippet,
            // or a lateinit var in your onAttach() or onCreate() method.
            val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                    if (isGranted) {
                        callback()
                    } else {
                        // Explain to the user that the feature is unavailable because the
                        // feature requires a permission that the user has denied. At the
                        // same time, respect the user's decision. Don't link to system
                        // settings in an effort to convince the user to change their
                        // decision.
                        Toast.makeText(
                            applicationContext,
                            "Couldn't get storage permissions",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            // You can directly ask for the permission.
            // The registered ActivityResultCallback gets the result of this request.
            requestPermissionLauncher.launch(permission.READ_EXTERNAL_STORAGE)
        }
    }

    //endregion
}