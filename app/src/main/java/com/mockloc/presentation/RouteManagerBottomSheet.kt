package com.mockloc.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mockloc.R
import com.mockloc.databinding.SheetRouteManagerBinding
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.presentation.adapter.SavedRouteAdapter
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.presentation.ui.EdgeToEdgeHelper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class RouteManagerBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetRouteManagerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory(requireContext())
    }

    private lateinit var adapter: SavedRouteAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetRouteManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        EdgeToEdgeHelper.applyBottomPaddingInsets(binding.routeManagerContent, includeIme = true)

        val screenHeight = resources.displayMetrics.heightPixels
        val minListPx = (160f * resources.displayMetrics.density).toInt()
        val maxListPx = (360f * resources.displayMetrics.density).toInt()
        binding.rvSavedRoutes.maxHeightPx = (screenHeight * 0.45f).toInt().coerceIn(minListPx, maxListPx)

        adapter = SavedRouteAdapter(
            onLoad = { route ->
                viewModel.loadRouteToDraft(route)
                dismiss()
            },
            onRename = { route -> showRenameDialog(route) },
            onDelete = { route -> showDeleteConfirmDialog(route) }
        )

        binding.rvSavedRoutes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSavedRoutes.adapter = adapter

        binding.btnResetSampleRoutes.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_route_title_reset)
                .setMessage(R.string.dialog_route_msg_reset)
                .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.resetSampleRoutes() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState
                        .map { it.savedRoutes }
                        .distinctUntilChanged()
                        .collect { routes ->
                            adapter.submitList(routes)
                            val empty = routes.isEmpty()
                            binding.tvRoutesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                            binding.rvSavedRoutes.visibility = if (empty) View.GONE else View.VISIBLE
                        }
                }
                launch {
                    viewModel.events.collect { event ->
                        if (event is MainUiEvent.RouteFeedback) {
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

    private fun showRenameDialog(route: PlannedRoute) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val til = TextInputLayout(ctx).apply { hint = getString(R.string.dialog_route_hint_new_name) }
        val et = TextInputEditText(ctx).apply { setText(route.name) }
        til.addView(et)
        container.addView(til)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_route_title_rename)
            .setView(container)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                viewModel.renameSavedRoute(route.id, et.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(route: PlannedRoute) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_route_title_delete)
            .setMessage(getString(R.string.dialog_route_msg_delete, route.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteSavedRoute(route.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
