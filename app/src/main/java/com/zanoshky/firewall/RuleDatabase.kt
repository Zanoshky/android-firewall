package com.zanoshky.firewall

import android.content.Context
import androidx.room.*

@Entity(tableName = "rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val allowWifi: Boolean = false,
    val allowMobile: Boolean = false
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

@Database(entities = [AppRule::class], version = 1, exportSchema = false)
abstract class RuleDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    companion object {
        @Volatile private var INSTANCE: RuleDatabase? = null

        fun get(context: Context): RuleDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, RuleDatabase::class.java, "firewall.db")
                    .build().also { INSTANCE = it }
            }
    }
}
