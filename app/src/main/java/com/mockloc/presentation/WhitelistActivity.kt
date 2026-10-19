package com.mockloc.presentation

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.mockloc.R
import com.mockloc.data.AppInfo
import com.mockloc.data.WhitelistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目标应用白名单选择器界面。
 * 展示系统已安装应用，支持关键字检索、系统应用过滤、批量全选/取消以及严格 opt-in 同步。
 */
class WhitelistActivity : AppCompatActivity() {

    private lateinit var repository: WhitelistRepository
    private lateinit var adapter: WhitelistAppAdapter

    private lateinit var etSearchApp: TextInputEditText
    private lateinit var cbShowSystemApps: MaterialCheckBox
    private lateinit var rvApps: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var tvSummary: TextView

    private val allApps = mutableListOf<AppInfo>()
    private val displayedApps = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)

        repository = WhitelistRepository.getInstance(this)

        initViews()
        loadInstalledApplications()
    }

    private fun initViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        etSearchApp = findViewById(R.id.etSearchApp)
        cbShowSystemApps = findViewById(R.id.cbShowSystemApps)
        rvApps = findViewById(R.id.rvApps)
        pbLoading = findViewById(R.id.pbLoading)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvSummary = findViewById(R.id.tvSummary)

        adapter = WhitelistAppAdapter(
            packageManager = packageManager,
            apps = displayedApps,
            onItemToggled = { app, isSelected ->
                app.isSelected = isSelected
                repository.toggleAppSelection(app.packageName, isSelected)
                updateSummary()
            }
        )

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        // 搜索输入监听
        etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 系统应用过滤切换
        cbShowSystemApps.setOnCheckedChangeListener { _, _ ->
            applyFilter()
        }

        // 全选
        findViewById<View>(R.id.btnSelectAll).setOnClickListener {
            repository.setAllSelected(displayedApps, true)
            adapter.notifyDataSetChanged()
            updateSummary()
        }

        // 取消全选
        findViewById<View>(R.id.btnDeselectAll).setOnClickListener {
            repository.setAllSelected(displayedApps, false)
            adapter.notifyDataSetChanged()
            updateSummary()
        }
    }

    private fun loadInstalledApplications() {
        pbLoading.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        tvEmptyState.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val apps = repository.loadInstalledApps(packageManager, excludeSelf = packageName)
            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(apps)
                pbLoading.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = etSearchApp.text?.toString()
        val showSystem = cbShowSystemApps.isChecked

        val filtered = repository.filterApps(allApps, query, showSystem)
        displayedApps.clear()
        displayedApps.addAll(filtered)
        adapter.notifyDataSetChanged()

        if (displayedApps.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
        } else {
            tvEmptyState.visibility = View.GONE
        }

        updateSummary()
    }

    private fun updateSummary() {
        val selectedCount = allApps.count { it.isSelected }
        val totalCount = allApps.size
        tvSummary.text = getString(R.string.whitelist_summary_format, selectedCount, totalCount)
    }

    /**
     * 已安装应用列表适配器
     */
    private class WhitelistAppAdapter(
        private val packageManager: PackageManager,
        private val apps: List<AppInfo>,
        private val onItemToggled: (AppInfo, Boolean) -> Unit
    ) : RecyclerView.Adapter<WhitelistAppAdapter.ViewHolder>() {

        private val defaultIcon: Drawable? by lazy {
            try {
                packageManager.defaultActivityIcon
            } catch (_: Throwable) {
                null
            }
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
            val tvSystemBadge: TextView = view.findViewById(R.id.tvSystemAppBadge)
            val switchMock: SwitchMaterial = view.findViewById(R.id.switchMockEnabled)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_whitelist, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.tvName.text = app.appName
            holder.tvPackage.text = app.packageName
            holder.tvSystemBadge.visibility = if (app.isSystemApp) View.VISIBLE else View.GONE

            // 避免复用错乱：先清空 listener 再设 checked
            holder.switchMock.setOnCheckedChangeListener(null)
            holder.switchMock.isChecked = app.isSelected

            holder.switchMock.setOnCheckedChangeListener { _, isChecked ->
                onItemToggled(app, isChecked)
            }

            holder.itemView.setOnClickListener {
                holder.switchMock.toggle()
            }

            // 异步或安全加载应用图标
            try {
                val icon = packageManager.getApplicationIcon(app.packageName)
                holder.ivIcon.setImageDrawable(icon)
            } catch (_: Throwable) {
                defaultIcon?.let { holder.ivIcon.setImageDrawable(it) }
            }
        }

        override fun getItemCount(): Int = apps.size
    }
}
