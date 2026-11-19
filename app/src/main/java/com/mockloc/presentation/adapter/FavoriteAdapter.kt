package com.mockloc.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mockloc.R
import com.mockloc.data.favorite.FavoriteUsageStats
import com.mockloc.databinding.ItemFavoriteBinding
import com.mockloc.domain.model.FavoriteLocation

data class FavoriteListItem(
    val favorite: FavoriteLocation,
    val useCount: Int
)

class FavoriteAdapter(
    private val onUseSingle: (FavoriteLocation) -> Unit,
    private val onAddRoute: (FavoriteLocation) -> Unit,
    private val onEdit: (FavoriteLocation) -> Unit,
    private val onDelete: (FavoriteLocation) -> Unit
) : ListAdapter<FavoriteListItem, FavoriteAdapter.ViewHolder>(DIFF) {

    fun submitData(items: List<FavoriteLocation>, stats: Map<String, FavoriteUsageStats>) {
        val mapped = items.map { fav ->
            FavoriteListItem(
                favorite = fav,
                useCount = stats[fav.id]?.useCount ?: 0
            )
        }
        submitList(mapped)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemFavoriteBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: FavoriteListItem) {
            val item = row.favorite
            val ctx = binding.root.context
            binding.tvFavName.text = item.name
            binding.tvFavAddress.text = item.address.ifBlank { ctx.getString(R.string.fav_no_address) }
            binding.tvFavCoords.text = ctx.getString(
                R.string.fav_coords_format,
                item.latitude,
                item.longitude,
                item.altitude
            )
            binding.tvFavUsage.text = if (row.useCount > 0) {
                ctx.getString(R.string.fav_usage_count_format, row.useCount)
            } else {
                ctx.getString(R.string.fav_usage_default)
            }

            binding.btnFavUseSingle.setOnClickListener { onUseSingle(item) }
            binding.btnFavAddRoute.setOnClickListener { onAddRoute(item) }
            binding.btnFavEdit.setOnClickListener { onEdit(item) }
            binding.btnFavDelete.setOnClickListener { onDelete(item) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<FavoriteListItem>() {
            override fun areItemsTheSame(oldItem: FavoriteListItem, newItem: FavoriteListItem): Boolean =
                oldItem.favorite.id == newItem.favorite.id

            override fun areContentsTheSame(oldItem: FavoriteListItem, newItem: FavoriteListItem): Boolean =
                oldItem == newItem
        }
    }
}
