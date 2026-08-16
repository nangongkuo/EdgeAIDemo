package com.zjf.edgeai.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.zjf.edgeai.R
import com.zjf.edgeai.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: EdgeAiViewModel by viewModels {
        EdgeAiViewModel.factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = EdgeAiPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.setText(
                when (position) {
                    0 -> R.string.tab_model
                    1 -> R.string.tab_chat
                    else -> R.string.tab_diagnostics
                }
            )
        }.attach()

        // Force creation through the activity owner so all tabs share one native runtime.
        viewModel.uiState.value
    }
}
