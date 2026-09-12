package com.zanoshky.firewall

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * How the firewall treats one app.
 *
 * The old model was two booleans, "allowed on Wi-Fi" and "allowed on mobile".
 * It said nothing about what actually happens to the traffic, and an allowed app
 * was excluded from the tunnel altogether, so no domain rule could ever apply to
 * it. The three modes below say exactly what each app gets instead.
 */
object AppMode {
    /** No working addresses and no connections. Every lookup is refused. */
    const val BLOCKED = 0

    /** Inside the tunnel. Lookups go through the firewall: DoH, trackers, domain rules. */
    const val FILTERED = 1

    /** Outside the tunnel. Untouched, unfiltered, and costs nothing. */
    const val BYPASS = 2

    fun label(mode: Int): String = when (mode) {
        FILTERED -> "Filtered"
        BYPASS -> "Bypass"
        else -> "Blocked"
    }
}

@Entity(tableName = "rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val mode: Int = AppMode.BLOCKED
)

/** Why a lookup or a connection ended the way it did. Also the Activity tab's filter. */
object BlockReason {
    const val ALLOWED = 0
    const val TRACKER = 1
    const val BLOCKLIST = 2
    const val APP_BLOCKED = 3
    const val DROPPED = 4

    fun label(reason: Int): String = when (reason) {
        TRACKER -> "TRACKER"
        BLOCKLIST -> "BLOCKED"
        APP_BLOCKED -> "APP BLOCKED"
        DROPPED -> "DROPPED"
        else -> "ALLOWED"
    }
}

@Entity(
    tableName = "connection_logs",
    indices = [Index("timestamp"), Index("domain"), Index("packageName")]
)
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val domain: String,
    val destIp: String,
    val destPort: Int,
    val protocol: String,
    val blockReason: Int,
    val bytes: Long,
    val timestamp: Long
) {
    val allowed: Boolean get() = blockReason == BlockReason.ALLOWED
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules")
    suspend fun getAll(): List<AppRule>

    @Upsert
    suspend fun upsert(rule: AppRule)

    @Upsert
    suspend fun upsertAll(rules: List<AppRule>)

    @Query("DELETE FROM rules WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    /** Used by restore, which replaces the rule set rather than merging into it. */
    @Query("DELETE FROM rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rules WHERE mode = :mode")
    suspend fun countByMode(mode: Int): Int
}

/** Per-app aggregate used by the Activity screen, computed in SQL instead of in memory. */
data class AppActivityAgg(
    val packageName: String,
    val appName: String,
    val total: Int,
    val blocked: Int
)

/** One domain and how often it was seen, for the top-domain lists. */
data class DomainCount(
    val domain: String,
    val hits: Int
)

/** One bucket of the last-24-hours histogram. [hour] is an epoch hour number. */
data class HourBucket(
    val hour: Long,
    val total: Int,
    val blocked: Int
)

@Dao
interface ConnectionLogDao {
    @Insert
    suspend fun insertAll(logs: List<ConnectionLog>)

    /**
     * Filtered log query. [status]: 0 = all, 1 = blocked (any reason), 2 = allowed,
     * 3 = trackers only. [query] matches app name, destination IP, or domain.
     */
    @Query(
        """
        SELECT * FROM connection_logs
        WHERE (:status = 0
            OR (:status = 1 AND blockReason != 0)
            OR (:status = 2 AND blockReason = 0)
            OR (:status = 3 AND blockReason = 1))
        AND (:query = ''
            OR appName LIKE '%' || :query || '%'
            OR destIp LIKE '%' || :query || '%'
            OR domain LIKE '%' || :query || '%')
        ORDER BY timestamp DESC LIMIT :limit
    """
    )
    suspend fun getFiltered(status: Int, query: String, limit: Int): List<ConnectionLog>

    @Query("SELECT COUNT(*) FROM connection_logs")
    suspend fun getCount(): Long

    @Query("SELECT COUNT(*) FROM connection_logs WHERE blockReason = :reason")
    suspend fun countByReason(reason: Int): Long

    @Query("SELECT COUNT(*) FROM connection_logs WHERE blockReason != 0")
    suspend fun countBlocked(): Long

    @Query("SELECT COUNT(DISTINCT domain) FROM connection_logs WHERE domain != ''")
    suspend fun countDistinctDomains(): Long

    @Query(
        """
        SELECT packageName, appName, COUNT(*) AS total,
               SUM(CASE WHEN blockReason != 0 THEN 1 ELSE 0 END) AS blocked
        FROM connection_logs
        GROUP BY packageName
        ORDER BY total DESC
        LIMIT :limit
    """
    )
    suspend fun getActivityByApp(limit: Int): List<AppActivityAgg>

    @Query(
        """
        SELECT domain, COUNT(*) AS hits FROM connection_logs
        WHERE domain != '' AND blockReason != 0
        GROUP BY domain ORDER BY hits DESC LIMIT :limit
    """
    )
    suspend fun getTopBlockedDomains(limit: Int): List<DomainCount>

    @Query(
        """
        SELECT domain, COUNT(*) AS hits FROM connection_logs
        WHERE domain != '' AND blockReason = 0
        GROUP BY domain ORDER BY hits DESC LIMIT :limit
    """
    )
    suspend fun getTopAllowedDomains(limit: Int): List<DomainCount>

    /** Hourly buckets since [since], for the small activity chart. */
    @Query(
        """
        SELECT (timestamp / 3600000) AS hour, COUNT(*) AS total,
               SUM(CASE WHEN blockReason != 0 THEN 1 ELSE 0 END) AS blocked
        FROM connection_logs
        WHERE timestamp >= :since
        GROUP BY hour ORDER BY hour ASC
    """
    )
    suspend fun getHourly(since: Long): List<HourBucket>

    @Query("DELETE FROM connection_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    /** Keep the newest [keep] rows and drop the rest, so the file cannot grow without end. */
    @Query(
        """
        DELETE FROM connection_logs WHERE id NOT IN
            (SELECT id FROM connection_logs ORDER BY timestamp DESC LIMIT :keep)
    """
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM connection_logs")
    suspend fun deleteAll()
}

@Database(
    entities = [AppRule::class, ConnectionLog::class],
    version = 6,
    exportSchema = false
)
abstract class RuleDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun connectionLogDao(): ConnectionLogDao

    companion object {
        @Volatile private var INSTANCE: RuleDatabase? = null

        /**
         * Rules are carried across by hand rather than destroyed: version 5 users
         * would otherwise lose every app they had allowed, which is the one thing
         * in this database worth keeping. An app that was allowed on either network
         * becomes Filtered, everything else stays Blocked.
         *
         * Logs and the old per-app byte counters are telemetry that no longer has a
         * matching shape, so those tables are simply rebuilt.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rules_new` " +
                        "(`packageName` TEXT NOT NULL, `mode` INTEGER NOT NULL, PRIMARY KEY(`packageName`))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `rules_new` (`packageName`, `mode`) " +
                        "SELECT `packageName`, CASE WHEN `allowWifi` = 1 OR `allowMobile` = 1 THEN 1 ELSE 0 END " +
                        "FROM `rules`"
                )
                db.execSQL("DROP TABLE `rules`")
                db.execSQL("ALTER TABLE `rules_new` RENAME TO `rules`")

                db.execSQL("DROP TABLE IF EXISTS `traffic_stats`")
                db.execSQL("DROP TABLE IF EXISTS `connection_logs`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `connection_logs` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                        "`appName` TEXT NOT NULL, `domain` TEXT NOT NULL, `destIp` TEXT NOT NULL, " +
                        "`destPort` INTEGER NOT NULL, `protocol` TEXT NOT NULL, `blockReason` INTEGER NOT NULL, " +
                        "`bytes` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_logs_timestamp` ON `connection_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_logs_domain` ON `connection_logs` (`domain`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_logs_packageName` ON `connection_logs` (`packageName`)")
            }
        }

        fun get(context: Context): RuleDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, RuleDatabase::class.java, "firewall.db"
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration(true)
                    .build().also { INSTANCE = it }
            }
    }
}
