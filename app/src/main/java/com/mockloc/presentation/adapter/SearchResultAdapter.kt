package com.mockloc.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mockloc.R
import com.mockloc.databinding.ItemSearchResultBinding
import com.mockloc.domain.geocoding.OfflineFallbackGeocodingService
import com.mockloc.domain.model.GeocodingResult

class SearchResultAdapter(
    private val onItemClick: (GeocodingResult) -> Unit
) : ListAdapter<GeocodingResult, SearchResultAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
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
        private val binding: ItemSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GeocodingResult) {
            val ctx = binding.root.context
            binding.tvResultName.text = item.name
            binding.tvResultAddress.text = item.address.ifBlank {
                item.city.ifBlank { ctx.getString(R.string.search_result_default_addr) }
            }
            binding.tvResultCoords.text = ctx.getString(
                R.string.search_result_coords_format,
                item.latitude,
                item.longitude
            )
            binding.tvResultSourceBadge.text = when (item.source) {
                "amap_web", "amap_regeo",
                "baidu_web", "arcgis_online",
                "photon_online", "photon_regeo",
                "nominatim_online", "nominatim_regeo",
                "open_meteo_online" -> ctx.getString(R.string.badge_source_online)
                OfflineFallbackGeocodingService.SOURCE_FALLBACK,
                OfflineFallbackGeocodingService.SOURCE_NEAREST,
                OfflineFallbackGeocodingService.SOURCE_COORD -> ctx.getString(R.string.badge_source_offline)
                else -> ctx.getString(R.string.badge_source_parsed)
            }
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<GeocodingResult>() {
            override fun areItemsTheSame(oldItem: GeocodingResult, newItem: GeocodingResult): Boolean =
                oldItem.name == newItem.name &&
                    oldItem.latitude == newItem.latitude &&
                    oldItem.longitude == newItem.longitude

            override fun areContentsTheSame(oldItem: GeocodingResult, newItem: GeocodingResult): Boolean =
                oldItem == newItem
        }
    }
}
