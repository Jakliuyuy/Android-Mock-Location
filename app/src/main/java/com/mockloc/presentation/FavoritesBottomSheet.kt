package com.mockloc.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
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
import com.mockloc.databinding.SheetFavoritesBinding
import com.mockloc.domain.model.FavoriteLocation
import com.mockloc.presentation.adapter.FavoriteAdapter
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.presentation.ui.EdgeToEdgeHelper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class FavoritesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetFavoritesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModelFactory(requireContext())
    }

    private lateinit var adapter: FavoriteAdapter
    private var isUpdatingSearch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        EdgeToEdgeHelper.applyBottomPaddingInsets(binding.favoritesSheetContent, includeIme = true)

        val screenHeight = resources.displayMetrics.heightPixels
        val minListPx = (160f * resources.displayMetrics.density).toInt()
        val maxListPx = (360f * resources.displayMetrics.density).toInt()
        binding.rvFavorites.maxHeightPx = (screenHeight * 0.40f).toInt().coerceIn(minListPx, maxListPx)

        adapter = FavoriteAdapter(
            onUseSingle = { fav ->
                viewModel.useFavoriteAsSingle(fav)
                dismiss()
            },
            onAddRoute = { fav ->
                viewModel.appendFavoriteToRoute(fav)
                dismiss()
            },
            onEdit = { fav -> showEditDialog(fav) },
            onDelete = { fav -> showDeleteConfirmDialog(fav) }
        )

        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFavorites.adapter = adapter

        binding.etFavoriteSearch.doAfterTextChanged { editable ->
            if (!isUpdatingSearch) {
                viewModel.updateFavoriteSearch(editable?.toString().orEmpty())
            }
        }

        binding.btnAddCurrentToFavorites.setOnClickListener {
            showAddCurrentDialog()
        }

        binding.btnResetFavorites.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_fav_title_reset)
                .setMessage(R.string.dialog_fav_msg_reset)
                .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.resetDefaultFavorites() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState
                        .map { Triple(it.favoriteSearchQuery, it.filteredFavorites, it.favoriteUsageStats) }
                        .distinctUntilChanged()
                        .collect { (query, favorites, stats) ->
                            if (binding.etFavoriteSearch.text?.toString() != query &&
                                !binding.etFavoriteSearch.hasFocus()
                            ) {
                                isUpdatingSearch = true
                                binding.etFavoriteSearch.setText(query)
                                isUpdatingSearch = false
                            }
                            adapter.submitData(favorites, stats)
                            val empty = favorites.isEmpty()
                            binding.tvFavoritesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                            binding.rvFavorites.visibility = if (empty) View.GONE else View.VISIBLE
                        }
                }
                launch {
                    viewModel.events.collect { event ->
                        if (event is MainUiEvent.FavoriteFeedback) {
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

    private fun showAddCurrentDialog() {
        val state = viewModel.uiState.value
        val point = state.selectedPoint
        val ctx = requireContext()
        val defaultHintLabel = getString(R.string.default_selected_address_hint)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val tilName = TextInputLayout(ctx).apply { hint = getString(R.string.dialog_fav_hint_name_required) }
        val etName = TextInputEditText(ctx).apply {
            setText(
                state.selectedAddressLabel
                    .takeIf { it.isNotBlank() && it != defaultHintLabel }
                    ?.substringBefore(" · ")
                    .orEmpty()
            )
        }
        tilName.addView(etName)

        val tilAddr = TextInputLayout(ctx).apply { hint = getString(R.string.dialog_fav_hint_addr_optional) }
        val etAddr = TextInputEditText(ctx).apply {
            setText(
                String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
            )
        }
        tilAddr.addView(etAddr)

        container.addView(tilName)
        container.addView(tilAddr)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_fav_title_add)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                viewModel.addFavorite(
                    name = etName.text?.toString().orEmpty(),
                    address = etAddr.text?.toString().orEmpty(),
                    point = point
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showEditDialog(fav: FavoriteLocation) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val tilName = TextInputLayout(ctx).apply { hint = getString(R.string.dialog_fav_hint_name) }
        val etName = TextInputEditText(ctx).apply { setText(fav.name) }
        tilName.addView(etName)

        val tilAddr = TextInputLayout(ctx).apply { hint = getString(R.string.dialog_fav_hint_addr) }
        val etAddr = TextInputEditText(ctx).apply { setText(fav.address) }
        tilAddr.addView(etAddr)

        container.addView(tilName)
        container.addView(tilAddr)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_fav_title_edit)
            .setView(container)
            .setPositiveButton(R.string.action_update) { _, _ ->
                viewModel.updateFavorite(
                    id = fav.id,
                    name = etName.text?.toString().orEmpty(),
                    address = etAddr.text?.toString().orEmpty()
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(fav: FavoriteLocation) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_fav_title_delete)
            .setMessage(getString(R.string.dialog_fav_msg_delete, fav.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.removeFavorite(fav.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
