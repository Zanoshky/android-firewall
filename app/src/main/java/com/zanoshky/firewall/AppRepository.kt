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
    var mode: Int = AppMode.BLOCKED,
    /** Lookups seen for this app in the kept history, blocked ones included. */
    var lookups: Int = 0,
    var blocked: Int = 0
)

object AppRepository {

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        // The firewall itself is always outside the tunnel, so offering a mode for
        // it would be a control that does nothing.
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.uid > 1000 && it.packageName != context.packageName }
            .map { info ->
                AppInfo(
                    name = info.loadLabel(pm).toString(),
                    packageName = info.packageName,
                    icon = info.loadIcon(pm),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    uid = info.uid,
                    mode = RuleStore.modeOf(info.packageName)
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.name.lowercase() }))
    }
}
