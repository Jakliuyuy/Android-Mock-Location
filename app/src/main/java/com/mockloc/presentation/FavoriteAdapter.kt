package com.mockloc.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mockloc.R
import com.mockloc.data.FavoriteLocation

class FavoriteAdapter(
    private var items: List<FavoriteLocation>,
    private val onApplyClick: (FavoriteLocation) -> Unit,
    private val onDeleteClick: (FavoriteLocation) -> Unit
) : RecyclerView.Adapter<FavoriteAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvFavName)
        val tvAddress: TextView = itemView.findViewById(R.id.tvFavAddress)
        val tvCoordinates: TextView = itemView.findViewById(R.id.tvFavCoordinates)
        val btnApply: MaterialButton = itemView.findViewById(R.id.btnApplyFav)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteFav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_location, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvAddress.text = if (item.address.isNotEmpty()) item.address else "经纬度自定义坐标"
        holder.tvCoordinates.text = "📍 %.6f, %.6f".format(item.latitude, item.longitude)

        holder.btnApply.setOnClickListener {
            onApplyClick(item)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<FavoriteLocation>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
