package com.zanoshky.firewall

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val isSystem: Boolean,
    val uid: Int,
    var allowWifi: Boolean = false,
    var allowMobile: Boolean = false,
    var blockedRequests: Long = 0,
    var allowedRequests: Long = 0
)

object AppRepository {

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.uid > 1000 }
            .map { info ->
                AppInfo(
                    name = info.loadLabel(pm).toString(),
                    packageName = info.packageName,
                    icon = info.loadIcon(pm),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    uid = info.uid
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.name.lowercase() }))
    }
}
