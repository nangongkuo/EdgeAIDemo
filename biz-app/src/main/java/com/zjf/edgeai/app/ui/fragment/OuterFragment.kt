package com.zjf.edgeai.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.zjf.edgeai.app.R
import com.zjf.edgeai.app.databinding.FragmentOuterBinding
import com.zjf.edgeai.app.ui.adapter.TabFragmentAdapter

/**
 * 外层OuterFragment (F1)
 * 其中一个页面包含内层ViewPager2 (B)
 */
class OuterFragment : Fragment() {

    companion object {
        private const val ARG_PAGE_INDEX = "page_index"
        private const val ARG_HAS_NESTED_VIEWPAGER = "has_nested_viewpager"

        fun newInstance(pageIndex: Int, hasNestedViewPager: Boolean): OuterFragment {
            return OuterFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PAGE_INDEX, pageIndex)
                    putBoolean(ARG_HAS_NESTED_VIEWPAGER, hasNestedViewPager)
                }
            }
        }
    }

    private var _binding: FragmentOuterBinding? = null
    private val binding get() = _binding!!

    private var pageIndex: Int = 0
    private var hasNestedViewPager: Boolean = false

    private var tabFragmentAdapter: TabFragmentAdapter? = null
    private var tabLayoutMediator: TabLayoutMediator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pageIndex = it.getInt(ARG_PAGE_INDEX, 0)
            hasNestedViewPager = it.getBoolean(ARG_HAS_NESTED_VIEWPAGER, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DataBindingUtil.inflate(inflater, R.layout.fragment_outer, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
    }

    private fun initViews() {
        binding.tvTitle.text = "页面 ${pageIndex + 1}"

        if (hasNestedViewPager) {
            binding.innerViewPager.visibility = View.VISIBLE
            binding.tabLayout.visibility = View.VISIBLE
            binding.tvContent.visibility = View.GONE
            setupInnerViewPager()
        } else {
            binding.innerViewPager.visibility = View.GONE
            binding.tabLayout.visibility = View.GONE
            binding.tvContent.visibility = View.VISIBLE
            binding.tvContent.text = "这是第 ${pageIndex + 1} 个页面\n\n左滑/右滑切换页面"
        }
    }

    private fun setupInnerViewPager() {
        val tabTitles = listOf("Tab 1", "Tab 2", "Tab 3")

        tabFragmentAdapter = TabFragmentAdapter(this, tabTitles)
        binding.innerViewPager.adapter = tabFragmentAdapter

        tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.innerViewPager) { tab, position ->
            tab.text = tabTitles.getOrElse(position) { "Tab $position" }
        }
        tabLayoutMediator?.attach()
    }

    fun getInnerViewPager(): ViewPager2? {
        return if (hasNestedViewPager) binding.innerViewPager else null
    }

    fun getOuterViewPager(): ViewPager2? {
        return activity?.findViewById(R.id.outerViewPager)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        _binding = null
    }
}
