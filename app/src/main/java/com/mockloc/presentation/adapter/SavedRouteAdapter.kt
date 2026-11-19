package com.mockloc.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mockloc.R
import com.mockloc.databinding.ItemSavedRouteBinding
import com.mockloc.domain.model.PlannedRoute
import java.util.Locale

class SavedRouteAdapter(
    private val onLoad: (PlannedRoute) -> Unit,
    private val onRename: (PlannedRoute) -> Unit,
    private val onDelete: (PlannedRoute) -> Unit
) : ListAdapter<PlannedRoute, SavedRouteAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedRouteBinding.inflate(
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
        private val binding: ItemSavedRouteBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlannedRoute) {
            val ctx = binding.root.context
            binding.tvRouteName.text = item.name
            val meters = item.totalDistanceMeters()
            val distanceText = if (meters >= 1000.0) {
                String.format(Locale.US, "%.2f km", meters / 1000.0)
            } else {
                String.format(Locale.US, "%.0f m", meters)
            }
            binding.tvRouteMeta.text = ctx.getString(R.string.route_meta_format, item.waypoints.size, distanceText)

            binding.btnRouteLoad.setOnClickListener { onLoad(item) }
            binding.btnRouteRename.setOnClickListener { onRename(item) }
            binding.btnRouteDelete.setOnClickListener { onDelete(item) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PlannedRoute>() {
            override fun areItemsTheSame(oldItem: PlannedRoute, newItem: PlannedRoute): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PlannedRoute, newItem: PlannedRoute): Boolean =
                oldItem == newItem
        }
    }
}
