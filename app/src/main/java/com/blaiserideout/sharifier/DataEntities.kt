package com.blaiserideout.sharifier

import android.graphics.Bitmap
import androidx.room.*
import java.time.Instant

@Entity(
    indices = [Index(value = ["path"], unique = true)]
)
data class FileItem(
    val path: String,
    val modified: Instant,
    @PrimaryKey(autoGenerate = true) val fileId: Int? = null,
)

@Entity()
data class Friend(
    val name: String,
    @PrimaryKey(autoGenerate = true) val friendId: Int? = null,
)

@Entity(
    primaryKeys = ["fileId", "friendId"],
    indices = [Index("fileId"), Index("friendId")]
)
data class SentItemsCrossRef(
    val fileId: Int,
    val friendId: Int,
)

@Entity(
    indices = [Index("fileId")]
)
data class Thumbnail(
    @PrimaryKey val fileId: Int,
    val thumbnail: Bitmap
)

data class FileAndThumbnail(
    @Embedded val file: FileItem,
    @Relation(
        parentColumn = "fileId",
        entityColumn = "fileId"
    )
    val thumb: Thumbnail
)

data class FileWithSentFriends(
    @Embedded val file: FileItem,
    @Relation(
        parentColumn = "fileId",
        entity = Friend::class,
        entityColumn = "friendId",
        associateBy = Junction(
            SentItemsCrossRef::class,
            parentColumn = "fileId",
            entityColumn = "friendId"
        )
    )
    val friends: MutableSet<Friend>
)