package com.zjf.edgeai.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.zjf.edgeai.ui.chat.ChatFragment
import com.zjf.edgeai.ui.diagnostics.DiagnosticsFragment
import com.zjf.edgeai.ui.model.ModelFragment

class EdgeAiPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ModelFragment()
        1 -> ChatFragment()
        else -> DiagnosticsFragment()
    }
}
