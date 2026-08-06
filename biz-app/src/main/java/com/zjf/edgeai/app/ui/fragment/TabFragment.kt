package com.zjf.edgeai.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.zjf.edgeai.app.R
import com.zjf.edgeai.app.databinding.FragmentTabBinding
import com.zjf.edgeai.app.ui.adapter.ListItem
import com.zjf.edgeai.app.ui.adapter.ListItemAdapter

/**
 * 内层TabFragment (F2)
 * 包含一个CustomRecyclerView (R)
 */
class TabFragment : Fragment() {

    companion object {
        private const val ARG_TAB_INDEX = "tab_index"
        private const val ARG_TAB_TITLE = "tab_title"

        fun newInstance(tabIndex: Int, tabTitle: String): TabFragment {
            return TabFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TAB_INDEX, tabIndex)
                    putString(ARG_TAB_TITLE, tabTitle)
                }
            }
        }
    }

    private var _binding: FragmentTabBinding? = null
    private val binding get() = _binding!!

    private var tabIndex: Int = 0
    private var tabTitle: String = ""

    private val listAdapter = ListItemAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            tabIndex = it.getInt(ARG_TAB_INDEX, 0)
            tabTitle = it.getString(ARG_TAB_TITLE, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DataBindingUtil.inflate(inflater, R.layout.fragment_tab, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        // 设置ViewPager引用
        setupViewPagerRefs()
    }

    private fun initViews() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = listAdapter
        }
    }

    private fun loadData() {
        val items = mutableListOf<ListItem>()
        val baseTitle = "Tab $tabIndex - Item"
        val itemCount = 20 + tabIndex * 10

        for (i in 1..itemCount) {
            items.add(
                ListItem(
                    id = i,
                    title = "$baseTitle $i",
                    description = "这是第 $tabIndex 个Tab的第 $i 条数据"
                )
            )
        }
        listAdapter.submitList(items)
    }

    private fun setupViewPagerRefs() {
        val outerFragment = parentFragment as? OuterFragment ?: return
        binding.recyclerView.setViewPagerRefs(
            inner = outerFragment.getInnerViewPager(),
            outer = outerFragment.getOuterViewPager()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
