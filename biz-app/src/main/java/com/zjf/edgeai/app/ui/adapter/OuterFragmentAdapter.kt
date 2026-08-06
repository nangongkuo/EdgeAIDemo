package com.zjf.edgeai.app.ui.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.zjf.edgeai.app.ui.fragment.OuterFragment

/**
 * 外层ViewPager2 (A) 的Adapter
 * 管理多个OuterFragment (F1)
 */
class OuterFragmentAdapter(
    activity: FragmentActivity
) : FragmentStateAdapter(activity) {

    companion object {
        const val PAGE_COUNT = 3
        const val PAGE_WITH_NESTED_VIEWPAGER = 1 // 第2个页面包含嵌套ViewPager
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun createFragment(position: Int): Fragment {
        return OuterFragment.newInstance(
            pageIndex = position,
            hasNestedViewPager = position == PAGE_WITH_NESTED_VIEWPAGER
        )
    }
}
