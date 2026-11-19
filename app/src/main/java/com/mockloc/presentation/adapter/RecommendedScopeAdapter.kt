package com.mockloc.presentation.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mockloc.R
import com.mockloc.data.scope.RecommendedAppItem
import com.mockloc.databinding.ItemRecommendedScopeBinding

class RecommendedScopeAdapter(
    private val onCopyPackage: (String) -> Unit
) : ListAdapter<RecommendedAppItem, RecommendedScopeAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecommendedScopeBinding.inflate(
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
        private val binding: ItemRecommendedScopeBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecommendedAppItem) {
            val ctx = binding.root.context
            binding.tvScopeAppName.text = item.appName
            binding.tvScopeCategory.text = item.category
            binding.tvScopePackageName.text = item.packageName
            binding.tvScopeDescription.text = item.description
            binding.tvScopeSystemBadge.visibility =
                if (item.isRequiredSystemScope) View.VISIBLE else View.GONE

            if (item.isInstalled) {
                binding.tvScopeInstallStatus.text = ctx.getString(R.string.badge_installed)
                binding.tvScopeInstallStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.status_success_container)
                )
                binding.tvScopeInstallStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.status_success)
                )
            } else {
                binding.tvScopeInstallStatus.text = ctx.getString(R.string.badge_not_installed)
                binding.tvScopeInstallStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.status_neutral_container)
                )
                binding.tvScopeInstallStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.status_neutral)
                )
            }

            binding.btnCopyPackage.setOnClickListener {
                onCopyPackage(item.packageName)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecommendedAppItem>() {
            override fun areItemsTheSame(
                oldItem: RecommendedAppItem,
                newItem: RecommendedAppItem
            ): Boolean = oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(
                oldItem: RecommendedAppItem,
                newItem: RecommendedAppItem
            ): Boolean = oldItem == newItem
        }
    }
}
