package net.mangadot.scaffold.ui.browser

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.entity.BrowserHistoryEntity

/** Pages from the browser's history. With [onRemove], each row has a button to forget it. */
class BrowserHistoryAdapter(
    private val onClick: (BrowserHistoryEntity) -> Unit,
    private val onRemove: ((BrowserHistoryEntity) -> Unit)? = null
) : ListAdapter<BrowserHistoryEntity, BrowserHistoryAdapter.Row>(DIFF) {

    class Row(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.history_tv_title)
        val url: TextView = view.findViewById(R.id.history_tv_url)
        val remove: ImageButton = view.findViewById(R.id.history_btn_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        return Row(LayoutInflater.from(parent.context).inflate(R.layout.item_browser_history, parent, false))
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        val visit = getItem(position)
        holder.title.text = visit.title.ifEmpty { Uri.parse(visit.url).host ?: visit.url }
        holder.url.text = visit.url
        holder.itemView.setOnClickListener { onClick(visit) }
        onRemove?.let { remove ->
            holder.remove.visibility = View.VISIBLE
            holder.remove.setOnClickListener { remove(visit) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BrowserHistoryEntity>() {
            override fun areItemsTheSame(oldItem: BrowserHistoryEntity, newItem: BrowserHistoryEntity) = oldItem.url == newItem.url
            override fun areContentsTheSame(oldItem: BrowserHistoryEntity, newItem: BrowserHistoryEntity) = oldItem == newItem
        }
    }
}
