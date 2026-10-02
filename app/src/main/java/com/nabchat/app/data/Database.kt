package com.nabchat.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels", indices = [Index(value = ["platform", "slug"], unique = true)])
data class ChannelEntity(
    @PrimaryKey val key: String,
    val platform: String,
    val platformChannelId: String,
    val chatroomId: String?,
    val slug: String,
    val displayName: String,
    val avatarUrl: String?,
    val enabled: Boolean,
    val dateAdded: Long,
    val sortOrder: Int,
    val favorite: Boolean
)

@Entity(
    tableName = "messages",
    indices = [Index("timestamp"), Index("channelKey"), Index("platformUserId"), Index("platform"), Index(value = ["channelKey", "timestamp", "receivedAt"])]
)
data class MessageEntity(
    @PrimaryKey val key: String,
    val platform: String,
    val messageId: String,
    val channelKey: String,
    val platformUserId: String?,
    val username: String,
    val displayName: String,
    val senderAvatarUrl: String?,
    val text: String,
    val timestamp: Long,
    val receivedAt: Long,
    val emotesJson: String,
    val badgesJson: String,
    val replyJson: String?,
    val isEmoteOnly: Boolean,
    val rawMetadata: String?
)

data class ChannelActivityRow(val channelKey: String, val latestActivity: Long)

@Entity(
    tableName = "hidden_users",
    indices = [Index("platform"), Index("platformUserId"), Index("username")]
)
data class HiddenUserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val platformUserId: String?,
    val username: String,
    val channelKey: String?,
    val scope: String,
    val hiddenAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_labels", indices = [Index("platform"), Index("platformUserId"), Index("username"), Index("label")])
data class UserLabelEntity(
    @PrimaryKey val key: String,
    val platform: String,
    val platformUserId: String?,
    val username: String,
    val displayName: String,
    val label: String,
    val labeledAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_chatters", indices = [Index("platform"), Index("platformUserId"), Index("username")])
data class SavedChatterEntity(
    @PrimaryKey val key: String,
    val platform: String,
    val platformUserId: String?,
    val username: String,
    val displayName: String,
    val colorArgb: Long,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "stream_sessions", indices = [Index("platform"), Index("channelKey"), Index("lastObservedAt")])
data class StreamSessionEntity(
    @PrimaryKey val key: String,
    val platform: String,
    val channelKey: String,
    val sessionId: String?,
    val streamStartedAt: Long?,
    val firstObservedAt: Long,
    val lastObservedAt: Long
)

@Dao interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY sortOrder, dateAdded") fun observeAll(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE `key`=:key LIMIT 1") suspend fun get(key: String): ChannelEntity?
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM channels") suspend fun maxSortOrder(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(channel: ChannelEntity)
    @Query("UPDATE channels SET sortOrder=:sortOrder WHERE `key`=:key") suspend fun setSortOrder(key: String, sortOrder: Int)
    @Transaction suspend fun reorder(keys: List<String>) { keys.forEachIndexed { index, key -> setSortOrder(key, index) } }
    @Query("UPDATE channels SET enabled=:enabled WHERE `key`=:key") suspend fun setEnabled(key: String, enabled: Boolean)
    @Query("UPDATE channels SET favorite=:favorite WHERE `key`=:key") suspend fun setFavorite(key: String, favorite: Boolean)
    @Query("DELETE FROM channels WHERE `key`=:key") suspend fun delete(key: String)
}

@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE channelKey=:channelKey ORDER BY timestamp DESC, receivedAt DESC LIMIT :limit") fun observeForChannel(channelKey: String, limit: Int): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages ORDER BY timestamp DESC, receivedAt DESC LIMIT :limit") suspend fun getRecent(limit: Int): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE platform=:platform AND ((:platformUserId IS NOT NULL AND platformUserId=:platformUserId) OR (:platformUserId IS NULL AND LOWER(username)=LOWER(:username))) ORDER BY timestamp DESC, receivedAt DESC") suspend fun getAllForUser(platform: String, platformUserId: String?, username: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE channelKey=:channelKey ORDER BY timestamp DESC, receivedAt DESC") suspend fun getAllForChannel(channelKey: String): List<MessageEntity>
    @Query("SELECT channelKey, MAX(CASE WHEN receivedAt > timestamp THEN receivedAt ELSE timestamp END) AS latestActivity FROM messages GROUP BY channelKey") fun observeChannelActivity(): Flow<List<ChannelActivityRow>>
    @Query("SELECT channelKey, MAX(CASE WHEN receivedAt > timestamp THEN receivedAt ELSE timestamp END) AS latestActivity FROM messages GROUP BY channelKey") suspend fun getChannelActivity(): List<ChannelActivityRow>
    @Query("SELECT * FROM messages ORDER BY timestamp, receivedAt, `key` LIMIT :limit OFFSET :offset") suspend fun exportPage(limit: Int, offset: Int): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(message: MessageEntity): Long
    @Query("SELECT COUNT(*) FROM messages") fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM messages") suspend fun count(): Int
    @Query("DELETE FROM messages") suspend fun deleteAll()
}

@Dao interface HiddenUserDao {
    @Query("SELECT * FROM hidden_users ORDER BY username") fun observeAll(): Flow<List<HiddenUserEntity>>
    @Insert suspend fun insert(hidden: HiddenUserEntity)
    @Delete suspend fun delete(hidden: HiddenUserEntity)
}

@Dao interface UserLabelDao {
    @Query("SELECT * FROM user_labels ORDER BY username") fun observeAll(): Flow<List<UserLabelEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(label: UserLabelEntity)
    @Delete suspend fun delete(label: UserLabelEntity)
}

@Dao interface SavedChatterDao {
    @Query("SELECT * FROM saved_chatters ORDER BY savedAt DESC") fun observeAll(): Flow<List<SavedChatterEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(chatter: SavedChatterEntity)
    @Query("UPDATE saved_chatters SET colorArgb=:colorArgb WHERE `key`=:key") suspend fun setColor(key: String, colorArgb: Long)
    @Delete suspend fun delete(chatter: SavedChatterEntity)
}

@Dao interface StreamSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(session: StreamSessionEntity)
}

@Database(entities = [ChannelEntity::class, MessageEntity::class, HiddenUserEntity::class, UserLabelEntity::class, SavedChatterEntity::class, StreamSessionEntity::class], version = 7, exportSchema = true)
abstract class NabchatDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun messages(): MessageDao
    abstract fun hiddenUsers(): HiddenUserDao
    abstract fun userLabels(): UserLabelDao
    abstract fun savedChatters(): SavedChatterDao
    abstract fun sessions(): StreamSessionDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_labels` (`key` TEXT NOT NULL, `platform` TEXT NOT NULL, `platformUserId` TEXT, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `label` TEXT NOT NULL, `labeledAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_labels_platform` ON `user_labels` (`platform`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_labels_platformUserId` ON `user_labels` (`platformUserId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_labels_username` ON `user_labels` (`username`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_labels_label` ON `user_labels` (`label`)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `channels` SET `sortOrder` = (SELECT COUNT(*) FROM `channels` AS older WHERE older.`dateAdded` < `channels`.`dateAdded`)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_channelKey_timestamp_receivedAt` ON `messages` (`channelKey`, `timestamp`, `receivedAt`)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `saved_chatters` (`key` TEXT NOT NULL, `platform` TEXT NOT NULL, `platformUserId` TEXT, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_chatters_platform` ON `saved_chatters` (`platform`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_chatters_platformUserId` ON `saved_chatters` (`platformUserId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_chatters_username` ON `saved_chatters` (`username`)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `senderAvatarUrl` TEXT")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, NabchatDatabase::class.java, "nabchat.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
    }
}
