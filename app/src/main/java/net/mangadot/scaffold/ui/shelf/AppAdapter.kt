package net.mangadot.scaffold.ui.shelf

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.userscript.UserscriptMeta

class AppAdapter(
    private val onAppClick: (WebAppEntity) -> Unit,
    private val onAppOptionsClick: (WebAppEntity) -> Unit
) : ListAdapter<WebAppEntity, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    enum class LayoutMode { ICONS, GRID, LIST }

    var currentLayoutMode: LayoutMode = LayoutMode.ICONS
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var scripts: List<ScriptEntity> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var blockedCounts: Map<String, Long> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** While arranging, holding an app starts a drag instead of opening its options. */
    var dragEnabled = false

    /** A folder shows names alone, the way a phone's folders do. */
    var showDomains = true

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
            if (dragEnabled) return@setOnLongClickListener false
            onAppOptionsClick(app)
            true
        }

        when (holder) {
            is IconViewHolder -> holder.bind(app)
            is GridViewHolder -> holder.bind(app)
            is ListViewHolder -> holder.bind(app)
        }
        if (app.id == DEMO_ID) showDemoIcon(holder.itemView)
    }

    /** The guide's demo app wears Scraffold's own logo. */
    private fun showDemoIcon(item: View) {
        val icon = listOf(R.id.iv_app_icon, R.id.grid_iv_app_icon, R.id.list_iv_app_icon)
            .firstNotNullOfOrNull { item.findViewById<ImageView>(it) } ?: return
        val monogram = listOf(R.id.tv_icon_monogram, R.id.grid_tv_icon_monogram, R.id.list_tv_icon_monogram)
            .firstNotNullOfOrNull { item.findViewById<View>(it) }
        icon.setImageResource(R.mipmap.ic_launcher)
        icon.visibility = View.VISIBLE
        monogram?.visibility = View.GONE
    }

    private fun cleanDomain(url: String): String {
        return Uri.parse(url).host ?: url.removePrefix("https://").removePrefix("http://").substringBefore('/')
    }

    private fun scriptCountLabel(app: WebAppEntity): String {
        val count = scripts.count { UserscriptMeta.parse(it.code).appliesTo(app.url) }
        return if (count == 1) "1 script" else "$count scripts"
    }

    private fun blockedLabel(app: WebAppEntity): String {
        return "${blockedCounts[app.id] ?: 0L} blocked"
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

    private fun getBranding(name: String): Pair<Int, Int> {
        val backgrounds = listOf(
            R.drawable.bg_squircle_emerald to R.color.accent_emerald,
            R.drawable.bg_squircle_purple to R.color.accent_purple_bright,
            R.drawable.bg_squircle_orange to R.color.text_primary,
            R.drawable.bg_squircle_cyan to R.color.accent_emerald_bright,
            R.drawable.bg_squircle_rose to R.color.accent_rose
        )
        val index = Math.abs(name.hashCode()) % backgrounds.size
        return backgrounds[index]
    }

    inner class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: FrameLayout = itemView.findViewById(R.id.icon_container)
        private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)
        private val tvDomain: TextView = itemView.findViewById(R.id.tv_app_domain)
        private val tvMonogram: TextView = itemView.findViewById(R.id.tv_icon_monogram)
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)

        fun bind(app: WebAppEntity) {
            tvName.text = app.name
            tvDomain.text = cleanDomain(app.url)
            tvDomain.visibility = if (showDomains) View.VISIBLE else View.GONE
            tvMonogram.text = extractMonogram(app.name)
            AppIcons.bind(ivIcon, tvMonogram, app)

            val (bgRes, textColorRes) = getBranding(app.name)
            container.setBackgroundResource(bgRes)
            tvMonogram.setTextColor(ContextCompat.getColor(itemView.context, textColorRes))
        }
    }

    inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: FrameLayout = itemView.findViewById(R.id.grid_icon_container)
        private val tvName: TextView = itemView.findViewById(R.id.grid_tv_app_name)
        private val tvUrl: TextView = itemView.findViewById(R.id.grid_tv_app_url)
        private val tvMonogram: TextView = itemView.findViewById(R.id.grid_tv_icon_monogram)
        private val ivIcon: ImageView = itemView.findViewById(R.id.grid_iv_app_icon)
        private val tvScripts: TextView = itemView.findViewById(R.id.grid_tv_scripts)
        private val tvBlocked: TextView = itemView.findViewById(R.id.grid_tv_blocked)
        private val btnOptions: ImageButton = itemView.findViewById(R.id.grid_btn_options)

        fun bind(app: WebAppEntity) {
            tvName.text = app.name
            tvUrl.text = cleanDomain(app.url)
            tvMonogram.text = extractMonogram(app.name)
            AppIcons.bind(ivIcon, tvMonogram, app)

            val (bgRes, textColorRes) = getBranding(app.name)
            container.setBackgroundResource(bgRes)
            tvMonogram.setTextColor(ContextCompat.getColor(itemView.context, textColorRes))

            tvScripts.text = scriptCountLabel(app)
            tvBlocked.text = blockedLabel(app)

            btnOptions.setOnClickListener {
                onAppOptionsClick(app)
            }
        }
    }

    inner class ListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: FrameLayout = itemView.findViewById(R.id.list_icon_container)
        private val tvName: TextView = itemView.findViewById(R.id.list_tv_app_name)
        private val tvUrl: TextView = itemView.findViewById(R.id.list_tv_app_url)
        private val tvMonogram: TextView = itemView.findViewById(R.id.list_tv_icon_monogram)
        private val ivIcon: ImageView = itemView.findViewById(R.id.list_iv_app_icon)
        private val tvBlocked: TextView = itemView.findViewById(R.id.list_tv_blocked)
        private val btnOptions: ImageButton = itemView.findViewById(R.id.list_btn_options)

        fun bind(app: WebAppEntity) {
            tvName.text = app.name
            tvUrl.text = cleanDomain(app.url)
            tvMonogram.text = extractMonogram(app.name)
            AppIcons.bind(ivIcon, tvMonogram, app)

            val (bgRes, textColorRes) = getBranding(app.name)
            container.setBackgroundResource(bgRes)
            tvMonogram.setTextColor(ContextCompat.getColor(itemView.context, textColorRes))

            tvBlocked.text = blockedLabel(app)

            btnOptions.setOnClickListener {
                onAppOptionsClick(app)
            }
        }
    }

    companion object {
        /** The app the guide shows on an empty shelf, which is never saved. */
        const val DEMO_ID = "scraffold-guide-demo"

        val DEMO_APP = WebAppEntity(id = DEMO_ID, name = "Demo app", url = "https://example.com")

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<WebAppEntity>() {
            override fun areItemsTheSame(oldItem: WebAppEntity, newItem: WebAppEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: WebAppEntity, newItem: WebAppEntity) = oldItem == newItem
        }
    }
}
