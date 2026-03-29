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

@Entity(tableName = "connection_logs")
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

    @Query("SELECT packageName FROM rules WHERE allowWifi = 1")
    suspend fun getAllowedWifi(): List<String>

    @Query("SELECT packageName FROM rules WHERE allowMobile = 1")
    suspend fun getAllowedMobile(): List<String>
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

@Dao
interface ConnectionLogDao {
    @Insert
    suspend fun insert(log: ConnectionLog)

    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ConnectionLog>

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
    version = 4,
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
