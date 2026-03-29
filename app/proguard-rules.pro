# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep VPN service
-keep class com.zanoshky.firewall.FirewallVpnService { *; }

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**
