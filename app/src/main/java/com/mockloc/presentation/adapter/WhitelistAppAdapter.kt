package com.mockloc.presentation.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mockloc.databinding.ItemWhitelistAppBinding
import com.mockloc.domain.model.AppScopeInfo
import com.mockloc.presentation.ui.AppIconLoader

class WhitelistAppAdapter(
    private val iconLoader: AppIconLoader,
    private val onTogglePackage: (String) -> Unit
) : ListAdapter<AppScopeInfo, WhitelistAppAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWhitelistAppBinding.inflate(
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
        private val binding: ItemWhitelistAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppScopeInfo) {
            iconLoader.load(item.packageName, binding.ivAppIcon)
            binding.tvAppName.text = item.appName
            binding.tvPackageName.text = item.packageName
            binding.tvBadgeRecommended.visibility = if (item.isRecommended) View.VISIBLE else View.GONE
            binding.tvBadgeSystem.visibility = if (item.isSystemApp) View.VISIBLE else View.GONE
            binding.switchAppSelected.isChecked = item.isSelected
            binding.root.setOnClickListener { onTogglePackage(item.packageName) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppScopeInfo>() {
            override fun areItemsTheSame(oldItem: AppScopeInfo, newItem: AppScopeInfo): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppScopeInfo, newItem: AppScopeInfo): Boolean =
                oldItem == newItem
        }
    }
}
