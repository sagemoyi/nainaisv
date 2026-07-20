package com.xmoyi.nainaisv.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "dramas",
    indices = [Index("ownerMid"), Index("bvid")],
)
data class DramaEntity(
    @androidx.room.PrimaryKey val id: String,
    val bvid: String,
    val cid: Long,
    val page: Int,
    val title: String,
    val ownerMid: Long,
    val ownerName: String,
    val coverUrl: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val publishedAt: Long,
    val playable: Boolean,
    val candidate: Boolean,
    val score: Int,
    val updatedAt: Long,
)

@Entity(tableName = "creators")
data class CreatorEntity(
    @androidx.room.PrimaryKey val mid: Long,
    val name: String,
    val trusted: Boolean = false,
    val blocked: Boolean = false,
    val lastSyncAt: Long = 0,
)

@Entity(
    tableName = "watch_states",
    indices = [Index("dramaId")],
    foreignKeys = [
        ForeignKey(
            entity = DramaEntity::class,
            parentColumns = ["id"],
            childColumns = ["dramaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WatchStateEntity(
    @androidx.room.PrimaryKey val dramaId: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completion: Float = 0f,
    val skipCount: Int = 0,
    val lastWatchedAt: Long = 0,
    val completed: Boolean = false,
)

data class DramaWithWatch(
    @Embedded val drama: DramaEntity,
    @Embedded(prefix = "watch_") val watch: WatchStateEntity?,
)

data class HistoryWithDrama(
    @Embedded val drama: DramaEntity,
    @Embedded(prefix = "watch_") val watch: WatchStateEntity,
)

@Dao
interface DramaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDramas(items: List<DramaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDrama(item: DramaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCreator(creator: CreatorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchState(state: WatchStateEntity)

    @Query("SELECT * FROM creators WHERE trusted = 1 AND blocked = 0 ORDER BY name")
    fun observeTrustedCreators(): Flow<List<CreatorEntity>>

    @Query("SELECT * FROM creators WHERE trusted = 1 AND blocked = 0 ORDER BY name")
    suspend fun getTrustedCreators(): List<CreatorEntity>

    @Query("SELECT * FROM creators WHERE trusted = 0 AND blocked = 0 ORDER BY name")
    fun observeCandidateCreators(): Flow<List<CreatorEntity>>

    @Query("SELECT * FROM creators WHERE blocked = 1 ORDER BY name")
    fun observeBlockedCreators(): Flow<List<CreatorEntity>>

    @Query("SELECT * FROM creators WHERE mid = :mid LIMIT 1")
    suspend fun getCreator(mid: Long): CreatorEntity?

    @Query("UPDATE creators SET trusted = :trusted, blocked = :blocked WHERE mid = :mid")
    suspend fun setCreatorStatus(mid: Long, trusted: Boolean, blocked: Boolean)

    @Query("UPDATE creators SET lastSyncAt = :time WHERE mid = :mid")
    suspend fun setCreatorSyncTime(mid: Long, time: Long)

    @Query("SELECT * FROM dramas WHERE ownerMid = :mid ORDER BY publishedAt DESC")
    fun observeCreatorDramas(mid: Long): Flow<List<DramaEntity>>

    @Query(
        """
        SELECT d.* FROM dramas d
        INNER JOIN creators c ON c.mid = d.ownerMid
        WHERE d.candidate = 1 AND d.playable = 1 AND c.blocked = 0
        ORDER BY d.score DESC, d.publishedAt DESC
        """,
    )
    fun observeCandidates(): Flow<List<DramaEntity>>

    @Query("SELECT * FROM dramas WHERE id = :id LIMIT 1")
    suspend fun getDrama(id: String): DramaEntity?

    @Query("SELECT * FROM dramas WHERE bvid = :bvid ORDER BY page")
    suspend fun getByBvid(bvid: String): List<DramaEntity>

    @Query("DELETE FROM dramas WHERE id = :id")
    suspend fun deleteDrama(id: String)

    @Query("UPDATE dramas SET playable = 0 WHERE id = :id")
    suspend fun markUnplayable(id: String)

    @Query("UPDATE dramas SET candidate = :candidate WHERE ownerMid = :mid")
    suspend fun setDramasCandidate(mid: Long, candidate: Boolean)

    @Transaction
    @Query(
        """
        SELECT d.*,
               w.dramaId AS watch_dramaId,
               w.positionMs AS watch_positionMs,
               w.durationMs AS watch_durationMs,
               w.completion AS watch_completion,
               w.skipCount AS watch_skipCount,
               w.lastWatchedAt AS watch_lastWatchedAt,
               w.completed AS watch_completed
        FROM dramas d
        INNER JOIN creators c ON c.mid = d.ownerMid
        LEFT JOIN watch_states w ON w.dramaId = d.id
        WHERE d.playable = 1 AND d.candidate = 0 AND c.trusted = 1 AND c.blocked = 0
        """,
    )
    suspend fun getPlayableWithWatch(): List<DramaWithWatch>

    @Query("SELECT * FROM watch_states WHERE dramaId = :dramaId LIMIT 1")
    suspend fun getWatchState(dramaId: String): WatchStateEntity?

    @Query("SELECT * FROM watch_states ORDER BY lastWatchedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int = 100): Flow<List<WatchStateEntity>>

    @Transaction
    @Query(
        """
        SELECT d.*,
               w.dramaId AS watch_dramaId,
               w.positionMs AS watch_positionMs,
               w.durationMs AS watch_durationMs,
               w.completion AS watch_completion,
               w.skipCount AS watch_skipCount,
               w.lastWatchedAt AS watch_lastWatchedAt,
               w.completed AS watch_completed
        FROM watch_states w
        INNER JOIN dramas d ON d.id = w.dramaId
        ORDER BY w.lastWatchedAt DESC
        LIMIT :limit
        """,
    )
    fun observeHistoryWithDrama(limit: Int = 100): Flow<List<HistoryWithDrama>>
}

@Database(
    entities = [DramaEntity::class, CreatorEntity::class, WatchStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dramaDao(): DramaDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "nainaisv.db",
            ).build().also { instance = it }
        }
    }
}

fun DramaItem.toEntity(now: Long = System.currentTimeMillis()) = DramaEntity(
    id = id,
    bvid = bvid,
    cid = cid,
    page = page,
    title = title,
    ownerMid = ownerMid,
    ownerName = ownerName,
    coverUrl = coverUrl,
    durationMs = durationMs,
    width = width,
    height = height,
    publishedAt = publishedAt,
    playable = playable,
    candidate = candidate,
    score = score,
    updatedAt = now,
)

fun DramaEntity.toModel() = DramaItem(
    id, bvid, cid, page, title, ownerMid, ownerName, coverUrl, durationMs,
    width, height, publishedAt, playable, candidate, score,
)
