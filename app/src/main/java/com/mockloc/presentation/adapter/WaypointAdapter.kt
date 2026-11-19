package com.mockloc.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mockloc.R
import com.mockloc.databinding.ItemWaypointBinding
import com.mockloc.domain.model.Waypoint
import java.util.Locale

class WaypointAdapter(
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onClick: (Int, Waypoint) -> Unit
) : RecyclerView.Adapter<WaypointAdapter.ViewHolder>() {

    private var items: List<Waypoint> = emptyList()

    fun submitList(newItems: List<Waypoint>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWaypointBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position, items[position], items.size)
    }

    inner class ViewHolder(
        private val binding: ItemWaypointBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int, item: Waypoint, total: Int) {
            val ctx = binding.root.context
            binding.tvWaypointIndex.text = (position + 1).toString()
            binding.tvWaypointName.text = item.name.ifBlank {
                ctx.getString(R.string.waypoint_default_name_format, position + 1)
            }
            binding.tvWaypointCoords.text = String.format(
                Locale.US,
                "%.6f, %.6f",
                item.point.latitude,
                item.point.longitude
            )
            binding.btnMoveUp.isEnabled = position > 0
            binding.btnMoveUp.alpha = if (position > 0) 1.0f else 0.35f
            binding.btnMoveDown.isEnabled = position < total - 1
            binding.btnMoveDown.alpha = if (position < total - 1) 1.0f else 0.35f

            binding.btnMoveUp.setOnClickListener {
                val idx = bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) onMoveUp(idx)
            }
            binding.btnMoveDown.setOnClickListener {
                val idx = bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) onMoveDown(idx)
            }
            binding.btnRemoveWaypoint.setOnClickListener {
                val idx = bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) onRemove(idx)
            }
            binding.root.setOnClickListener {
                val idx = bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) onClick(idx, item)
            }
        }
    }
}
