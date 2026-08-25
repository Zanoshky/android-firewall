package com.zanoshky.firewall

import android.content.Context
import androidx.room.*

@Entity(tableName = "rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val allowWifi: Boolean = false,
    val allowMobile: Boolean = false
)

@Entity(tableName = "traffic_stats")
data class TrafficStat(
    @PrimaryKey val packageName: String,
    val blockedRequests: Long = 0,
    val allowedRequests: Long = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val lastBlocked: Long = 0,
    val lastAllowed: Long = 0
)

@Entity(
    tableName = "connection_logs",
    indices = [Index("timestamp"), Index("appName")]
)
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val destIp: String,
    val destPort: Int,
    val protocol: String,
    val allowed: Boolean,
    val bytes: Long = 0,
    val timestamp: Long,
    val domain: String = "",
    val blockedByTracker: Boolean = false
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules")
    suspend fun getAll(): List<AppRule>

    @Query("SELECT * FROM rules WHERE packageName = :pkg")
    suspend fun getRule(pkg: String): AppRule?

    @Upsert
    suspend fun upsert(rule: AppRule)

    @Upsert
    suspend fun upsertAll(rules: List<AppRule>)

    @Query("DELETE FROM rules WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    /** Used by restore, which replaces the rule set rather than merging into it. */
    @Query("DELETE FROM rules")
    suspend fun deleteAll()

    @Query("SELECT packageName FROM rules WHERE allowWifi = 1")
    suspend fun getAllowedWifi(): List<String>

    @Query("SELECT packageName FROM rules WHERE allowMobile = 1")
    suspend fun getAllowedMobile(): List<String>

    @Query("SELECT COUNT(*) FROM rules WHERE allowWifi = 1 OR allowMobile = 1")
    suspend fun countAllowed(): Int

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun countAll(): Int
}

@Dao
interface TrafficDao {
    @Query("SELECT * FROM traffic_stats WHERE packageName = :pkg")
    suspend fun getStats(pkg: String): TrafficStat?

    @Query("SELECT * FROM traffic_stats")
    suspend fun getAll(): List<TrafficStat>

    @Query("SELECT SUM(blockedRequests) FROM traffic_stats")
    suspend fun getTotalBlocked(): Long?

    @Query("SELECT SUM(allowedRequests) FROM traffic_stats")
    suspend fun getTotalAllowed(): Long?

    @Query("SELECT SUM(bytesIn) FROM traffic_stats")
    suspend fun getTotalBytesIn(): Long?

    @Query("SELECT SUM(bytesOut) FROM traffic_stats")
    suspend fun getTotalBytesOut(): Long?

    @Upsert
    suspend fun upsert(stat: TrafficStat)

    @Query("UPDATE traffic_stats SET blockedRequests = blockedRequests + 1, lastBlocked = :ts WHERE packageName = :pkg")
    suspend fun incrementBlocked(pkg: String, ts: Long)

    @Query("UPDATE traffic_stats SET allowedRequests = allowedRequests + 1, lastAllowed = :ts WHERE packageName = :pkg")
    suspend fun incrementAllowed(pkg: String, ts: Long)

    @Query("UPDATE traffic_stats SET bytesIn = bytesIn + :bytes WHERE packageName = :pkg")
    suspend fun addBytesIn(pkg: String, bytes: Long)

    @Query("UPDATE traffic_stats SET bytesOut = bytesOut + :bytes WHERE packageName = :pkg")
    suspend fun addBytesOut(pkg: String, bytes: Long)

    @Query("SELECT * FROM traffic_stats ORDER BY (bytesIn + bytesOut) DESC LIMIT :limit")
    suspend fun getTopByTraffic(limit: Int): List<TrafficStat>
}

/** Per-app aggregate used by the Stats screen, computed in SQL instead of in memory. */
data class AppTrafficAgg(
    val appName: String,
    val totalBytes: Long,
    val entryCount: Int
)

@Dao
interface ConnectionLogDao {
    @Insert
    suspend fun insert(log: ConnectionLog)

    @Insert
    suspend fun insertAll(logs: List<ConnectionLog>)

    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ConnectionLog>

    /**
     * Filtered log query. [status]: 0 = all, 1 = blocked, 2 = allowed, 3 = trackers.
     * [query] matches app name, destination IP, or domain (empty = no text filter).
     */
    @Query("""
        SELECT * FROM connection_logs
        WHERE (:status = 0
            OR (:status = 1 AND allowed = 0 AND blockedByTracker = 0)
            OR (:status = 2 AND allowed = 1)
            OR (:status = 3 AND blockedByTracker = 1))
        AND (:query = ''
            OR appName LIKE '%' || :query || '%'
            OR destIp LIKE '%' || :query || '%'
            OR domain LIKE '%' || :query || '%')
        ORDER BY timestamp DESC LIMIT :limit
    """)
    suspend fun getFiltered(status: Int, query: String, limit: Int): List<ConnectionLog>

    @Query("""
        SELECT appName, SUM(bytes) AS totalBytes, COUNT(*) AS entryCount
        FROM connection_logs
        GROUP BY appName
        ORDER BY totalBytes DESC
        LIMIT :limit
    """)
    suspend fun getTrafficByApp(limit: Int): List<AppTrafficAgg>

    @Query("SELECT * FROM connection_logs WHERE packageName = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getForApp(pkg: String, limit: Int): List<ConnectionLog>

    @Query("SELECT COUNT(*) FROM connection_logs")
    suspend fun getCount(): Long

    @Query("SELECT COUNT(*) FROM connection_logs WHERE allowed = 0")
    suspend fun getBlockedCount(): Long

    @Query("SELECT COUNT(*) FROM connection_logs WHERE blockedByTracker = 1")
    suspend fun getTrackerBlockedCount(): Long

    @Query("DELETE FROM connection_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM connection_logs")
    suspend fun deleteAll()
}

@Database(
    entities = [AppRule::class, TrafficStat::class, ConnectionLog::class],
    version = 5,
    exportSchema = false
)
abstract class RuleDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun trafficDao(): TrafficDao
    abstract fun connectionLogDao(): ConnectionLogDao

    companion object {
        @Volatile private var INSTANCE: RuleDatabase? = null

        fun get(context: Context): RuleDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, RuleDatabase::class.java, "firewall.db")
                    .fallbackToDestructiveMigration(true)
                    .build().also { INSTANCE = it }
            }
    }
}
