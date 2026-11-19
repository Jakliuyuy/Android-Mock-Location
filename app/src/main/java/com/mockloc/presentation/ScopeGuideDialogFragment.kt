package com.mockloc.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.mockloc.R
import com.mockloc.databinding.DialogScopeGuideBinding
import com.mockloc.presentation.adapter.RecommendedScopeAdapter
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.presentation.ui.EdgeToEdgeHelper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ScopeGuideDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogScopeGuideBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory(requireContext())
    }

    private lateinit var adapter: RecommendedScopeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogScopeGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        EdgeToEdgeHelper.applyBottomPaddingInsets(binding.scopeGuideContent, includeIme = false)

        val screenHeight = resources.displayMetrics.heightPixels
        val minListPx = (160f * resources.displayMetrics.density).toInt()
        val maxListPx = (340f * resources.displayMetrics.density).toInt()
        binding.rvRecommendedScopes.maxHeightPx = (screenHeight * 0.38f).toInt().coerceIn(minListPx, maxListPx)

        adapter = RecommendedScopeAdapter { pkg ->
            viewModel.copyPackageName(pkg)
        }
        binding.rvRecommendedScopes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecommendedScopes.adapter = adapter

        binding.btnCloseScopeGuide.setOnClickListener { dismiss() }
        binding.btnScopeImportAll.setOnClickListener {
            viewModel.importRecommendedInstalledToWhitelist()
        }

        viewModel.refreshRecommendedScopes()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState
                        .map { it.recommendedScopes to it.scopeSummary }
                        .distinctUntilChanged()
                        .collect { (scopes, summary) ->
                            adapter.submitList(scopes)
                            binding.tvScopeSummaryStats.text = getString(
                                R.string.scope_summary_stats_format,
                                summary.installedTargetCount,
                                summary.totalTargetCount,
                                summary.totalAllCount
                            )
                        }
                }
                launch {
                    viewModel.events.collect { event ->
                        if (event is MainUiEvent.ScopeGuideFeedback) {
                            showModalSnackbar(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun showModalSnackbar(message: String) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
        val bottomInset = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        if (bottomInset > 0) {
            snackbar.view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin += bottomInset
            }
        }
        snackbar.show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
