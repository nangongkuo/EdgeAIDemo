package com.zjf.edgeai.app.ui

import android.os.Bundle
import com.zjf.edgeai.app.databinding.ActivityMainBinding
import com.zjf.edgeai.app.ui.adapter.OuterFragmentAdapter
import com.zjf.yflib.ui.activity.BaseActivity

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var outerFragmentAdapter: OuterFragmentAdapter

    override fun bindLayout(savedInstanceState: Bundle?) {
        super.bindLayout(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun initView() {
        super.initView()
        setupViewPager()
    }

    private fun setupViewPager() {
        outerFragmentAdapter = OuterFragmentAdapter(this)
        binding.outerViewPager.adapter = outerFragmentAdapter
    }
}
