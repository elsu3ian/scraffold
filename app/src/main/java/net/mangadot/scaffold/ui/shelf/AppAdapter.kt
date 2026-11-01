package net.mangadot.scaffold.ui.shelf

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.entity.WebAppEntity

class AppAdapter(
    private val onAppClick: (WebAppEntity) -> Unit,
    private val onAppLongClick: (WebAppEntity) -> Unit
) : ListAdapter<WebAppEntity, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    enum class LayoutMode { ICONS, GRID, LIST }

    var currentLayoutMode: LayoutMode = LayoutMode.ICONS
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        return when (currentLayoutMode) {
            LayoutMode.ICONS -> R.layout.item_app_icon
            LayoutMode.GRID -> R.layout.item_app_grid
            LayoutMode.LIST -> R.layout.item_app_list
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return when (viewType) {
            R.layout.item_app_icon -> IconViewHolder(view)
            R.layout.item_app_grid -> GridViewHolder(view)
            R.layout.item_app_list -> ListViewHolder(view)
            else -> IconViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val app = getItem(position)
        holder.itemView.setOnClickListener { onAppClick(app) }
        holder.itemView.setOnLongClickListener {
            onAppLongClick(app)
            true
        }

        when (holder) {
            is IconViewHolder -> holder.bind(app)
            is GridViewHolder -> holder.bind(app)
            is ListViewHolder -> holder.bind(app)
        }
    }

    private fun extractMonogram(name: String): String {
        val words = name.trim().split("\\s+".toRegex())
        return when {
            words.size >= 2 -> "${words[0].firstOrNull() ?: 'S'}${words[1].firstOrNull() ?: 'C'}".uppercase()
            name.length >= 2 -> name.take(2).uppercase()
            name.isNotEmpty() -> name.first().uppercase()
            else -> "SC"
        }
    }

    inner class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)
        private val tvMonogram: TextView = itemView.findViewById(R.id.tv_icon_monogram)

        fun bind(app: WebAppEntity) {
            tvName.text = app.name
            tvMonogram.text = extractMonogram(app.name)
        }
    }

    inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.grid_tv_app_name)
        private val tvUrl: TextView = itemView.findViewById(R.id.grid_tv_app_url)
        private val tvMonogram: TextView = itemView.findViewById(R.id.grid_tv_icon_monogram)

        fun bind(app: WebAppEntity) {
            tvName.text = app.name
            tvUrl.text = Uri.parse(app.url).host ?: app.url
            tvMonogram.text = extractMonogram(app.name)
        }
    }

    inner class ListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.list_tv_app_name)
        private val tvUrl: TextView = itemView.findViewById(R.id.list_tv_app_url)
        private val tvMonogram: TextView = itemView.findViewById(R.id.list_tv_icon_monogram)
        private val tvLaunches: TextView = itemView.findViewById(R.id.list_tv_launch_count)

        fun bind(app: WebAppEntity) {
            tvName.text = app.name
            tvUrl.text = app.url
            tvMonogram.text = extractMonogram(app.name)
            tvLaunches.text = "${app.launchCount} launches"
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<WebAppEntity>() {
            override fun areItemsTheSame(oldItem: WebAppEntity, newItem: WebAppEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: WebAppEntity, newItem: WebAppEntity) = oldItem == newItem
        }
    }
}
