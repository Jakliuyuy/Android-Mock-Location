package com.mockloc.presentation

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.mockloc.R
import com.mockloc.databinding.ActivityWhitelistBinding
import com.mockloc.presentation.adapter.WhitelistAppAdapter
import com.mockloc.presentation.model.WhitelistUiEvent
import com.mockloc.presentation.ui.AppIconLoader
import com.mockloc.presentation.ui.EdgeToEdgeHelper
import kotlinx.coroutines.launch

class WhitelistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWhitelistBinding

    private val viewModel: WhitelistViewModel by viewModels {
        WhitelistViewModelFactory(this)
    }

    private lateinit var adapter: WhitelistAppAdapter
    private var isUpdatingSwitch = false
    private var isUpdatingSearch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeHelper.applyTopPaddingInsets(binding.toolbarWhitelist)
        EdgeToEdgeHelper.applyBottomPaddingInsets(binding.rvWhitelistApps, includeIme = true)

        binding.toolbarWhitelist.setNavigationOnClickListener { finish() }

        val iconLoader = AppIconLoader(this, lifecycleScope)
        adapter = WhitelistAppAdapter(iconLoader) { packageName ->
            viewModel.togglePackage(packageName)
        }
        binding.rvWhitelistApps.layoutManager = LinearLayoutManager(this)
        binding.rvWhitelistApps.adapter = adapter

        binding.etWhitelistSearch.doAfterTextChanged { editable ->
            if (!isUpdatingSearch) {
                viewModel.updateSearchQuery(editable?.toString().orEmpty())
            }
        }

        binding.switchIncludeSystem.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingSwitch) {
                viewModel.setIncludeSystemApps(isChecked)
            }
        }

        binding.btnWhitelistSelectAll.setOnClickListener {
            viewModel.selectAllFiltered()
        }

        binding.btnWhitelistClearFiltered.setOnClickListener {
            viewModel.clearFiltered()
        }

        binding.btnImportRecommended.setOnClickListener {
            viewModel.importRecommendedInstalled()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.pbWhitelistLoading.visibility =
                            if (state.isLoading) View.VISIBLE else View.GONE

                        binding.toolbarWhitelist.subtitle = getString(
                            R.string.whitelist_selected_subtitle_format,
                            state.selectedPackages.size
                        )

                        if (binding.switchIncludeSystem.isChecked != state.includeSystemApps) {
                            isUpdatingSwitch = true
                            binding.switchIncludeSystem.isChecked = state.includeSystemApps
                            isUpdatingSwitch = false
                        }

                        if (!binding.etWhitelistSearch.hasFocus() &&
                            binding.etWhitelistSearch.text?.toString() != state.searchQuery
                        ) {
                            isUpdatingSearch = true
                            binding.etWhitelistSearch.setText(state.searchQuery)
                            isUpdatingSearch = false
                        }

                        adapter.submitList(state.filteredApps)
                        val showEmpty = !state.isLoading && state.filteredApps.isEmpty()
                        binding.tvWhitelistEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        if (event is WhitelistUiEvent.ShowMessage) {
                            val snackbar = Snackbar.make(binding.root, event.message, Snackbar.LENGTH_SHORT)
                            val bottomInset = ViewCompat.getRootWindowInsets(binding.root)
                                ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
                            if (bottomInset > 0) {
                                snackbar.view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                                    bottomMargin += bottomInset
                                }
                            }
                            snackbar.show()
                        }
                    }
                }
            }
        }
    }
}
