package com.zanoshky.firewall

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 4
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> AppsFragment()
        1 -> LogsFragment()
        2 -> StatsFragment()
        3 -> BlocklistFragment()
        else -> AppsFragment()
    }
}
