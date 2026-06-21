package com.example.networkstatslogger

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "network_logs")
data class NetworkLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: String,
    val deviceId: String,
    val deviceMake: String, // NEW
    val deviceModel: String, // NEW
    val carrierName: String,
    val networkType: String,
    val rsrp: String,
    val rsrq: String,
    val sinr: String,
    val pci: String,
    val downlinkSpeed: String,
    val uplinkSpeed: String,
    val velocity: String,
    val latitude: String,
    val longitude: String
)

@Dao
interface NetworkLogDao {
    @Insert
    suspend fun insert(log: NetworkLog)

    @Query("SELECT * FROM network_logs ORDER BY id DESC")
    suspend fun getAllLogs(): List<NetworkLog>

    // ── Cursor-based pagination ──────────────────────────────────────────────

    /** Load the first page (newest [pageSize] rows). */
    @Query("SELECT * FROM network_logs ORDER BY id DESC LIMIT :pageSize")
    suspend fun getInitialLogs(pageSize: Int): List<NetworkLog>

    /** Load the next page of rows older than [lastId]. */
    @Query("SELECT * FROM network_logs WHERE id < :lastId ORDER BY id DESC LIMIT :pageSize")
    suspend fun getLogsBeforeId(lastId: Int, pageSize: Int): List<NetworkLog>

    /** Live count of rows newer than [anchorId] — drives the "N new logs" badge. */
    @Query("SELECT COUNT(*) FROM network_logs WHERE id > :anchorId")
    fun countLogsNewerThan(anchorId: Int): Flow<Int>

    /** Fetch all rows newer than [anchorId] for the prepend operation (newest first). */
    @Query("SELECT * FROM network_logs WHERE id > :anchorId ORDER BY id DESC")
    suspend fun getLogsNewerThanId(anchorId: Int): List<NetworkLog>

    // ────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM network_logs ORDER BY id DESC LIMIT 10")
    fun getRecentLogs(): Flow<List<NetworkLog>>

    @Query("DELETE FROM network_logs")
    suspend fun clearAllLogs()

    @Query("SELECT * FROM network_logs ORDER BY id ASC LIMIT :chunkSize")
    suspend fun getOldestLogs(chunkSize: Int): List<NetworkLog>

    @Query("DELETE FROM network_logs WHERE id IN (:logIds)")
    suspend fun deleteLogsByIds(logIds: List<Int>)
}

// NEW: Incremented database version to 7
@Database(entities = [NetworkLog::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun networkLogDao(): NetworkLogDao
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "network_stats_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}