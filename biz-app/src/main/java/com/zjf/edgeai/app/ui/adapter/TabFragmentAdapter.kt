package com.zjf.edgeai.app.ui.adapter

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.zjf.edgeai.app.ui.fragment.TabFragment

/**
 * 内层ViewPager2 (B) 的Adapter
 * 管理3个TabFragment (F2)
 */
class TabFragmentAdapter(
    fragment: Fragment,
    private val tabTitles: List<String>
) : FragmentStateAdapter(fragment) {

    companion object {
        const val TAB_COUNT = 3
    }

    override fun getItemCount(): Int = TAB_COUNT

    override fun createFragment(position: Int): Fragment {
        return TabFragment.newInstance(
            tabIndex = position,
            tabTitle = tabTitles.getOrElse(position) { "Tab $position" }
        )
    }
}
