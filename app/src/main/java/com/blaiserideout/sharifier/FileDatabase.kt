package com.blaiserideout.sharifier

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.*

@Dao
interface FilesDao {
    @Query("SELECT * FROM FileItem ORDER BY modified DESC")
    fun getFiles(): List<FileItem>

    @Query("SELECT COUNT(*) FROM FileItem")
    fun getNumFiles(): Int

    @Transaction
    @Query("SELECT * FROM FileItem ORDER BY modified DESC")
    fun getFilesWithSentFriends(): List<FileWithSentFriends>

    @Transaction
    @Query(
        """SELECT MAX(modified) From FileItem
           INNER JOIN SentItemsCrossRef ON SentItemsCrossRef.fileId = FileItem.fileId
           WHERE friendId = :friendId"""
    )
    fun getLastSentFileForFriend(friendId: Int): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun sentFile(friend: SentItemsCrossRef)

    @Delete
    fun unsendFile(sentItem: SentItemsCrossRef)

    @Query("SELECT modified FROM FileItem ORDER BY modified DESC LIMIT 1")
    fun getLatest(): Instant?

    @Insert(onConflict=OnConflictStrategy.IGNORE)
    fun insertAll(files: List<FileItem>)

    @Delete
    fun delete(file: FileItem)

    @Delete
    fun deleteAll(file: List<FileItem>)

    @Query("DELETE FROM FileItem")
    fun reset()
}

@Dao
interface ThumbnailDao {
    @Query("""SELECT * FROM Thumbnail WHERE FileId = :fileId""")
    fun getThumbnailFor(fileId: Int): Thumbnail?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(thumb: Thumbnail)

    @Delete
    fun delete(thumb: Thumbnail)
}

@Dao
interface FriendsDao {
    @Query("SELECT * FROM Friend")
    fun getFriends(): List<Friend>

    @Insert
    fun insert(friend: Friend)

    @Delete
    fun delete(friend: Friend)
}


class DateTimeConverter {
    @TypeConverter
    fun fromTimestamp(timestamp: Long?): Instant? = timestamp?.let { Instant.ofEpochSecond(it) }

    @TypeConverter
    fun toTimestamp(timestamp: Instant?): Long? = timestamp?.epochSecond
}

class BitmapConverter {
    @TypeConverter
    fun fromBlob(blob: ByteArray?): Bitmap? =
        blob?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    @TypeConverter
    fun toBlob(bitmap: Bitmap?): ByteArray? = bitmap?.let {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        stream.toByteArray()
    }
}

@Database(
    entities = [FileItem::class, Friend::class, SentItemsCrossRef::class, Thumbnail::class],
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
    exportSchema = true,
    version = 3
)
@TypeConverters(DateTimeConverter::class, BitmapConverter::class)
abstract class FileDatabase : RoomDatabase() {
    abstract fun filesDao(): FilesDao
    abstract fun friendsDao(): FriendsDao
    abstract fun thumbsDao(): ThumbnailDao
}