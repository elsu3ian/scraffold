package net.mangadot.scaffold.ui.userscript

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.entity.ScriptEntity
import net.mangadot.scaffold.engine.userscript.UserscriptMeta

class UserscriptAdapter(
    private val onScriptClick: (ScriptEntity) -> Unit,
    private val onToggleEnabled: (ScriptEntity, Boolean) -> Unit,
    private val onMoreClick: (ScriptEntity, View) -> Unit
) : ListAdapter<ScriptEntity, UserscriptAdapter.ScriptViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_userscript, parent, false)
        return ScriptViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int) {
        val script = getItem(position)
        holder.bind(script)
    }

    inner class ScriptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_script_name)
        private val tvDescription: TextView = itemView.findViewById(R.id.tv_script_description)
        private val tvPattern: TextView = itemView.findViewById(R.id.tv_script_pattern)
        private val tvPhase: TextView = itemView.findViewById(R.id.tv_script_phase)
        private val btnEdit: TextView = itemView.findViewById(R.id.btn_script_edit)
        private val btnMore: View = itemView.findViewById(R.id.btn_script_more)
        private val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switch_script_enabled)

        fun bind(script: ScriptEntity) {
            val meta = UserscriptMeta.parse(script.code)
            tvName.text = if (meta.version.isEmpty()) script.name else "${script.name} v${meta.version}"
            tvDescription.text = meta.description
            tvDescription.visibility = if (meta.description.isEmpty()) View.GONE else View.VISIBLE
            tvPattern.text = (meta.matches + meta.includes).joinToString("  ")
            tvPhase.text = "Run at: ${meta.runAt}"

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = script.enabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(script, isChecked)
            }

            itemView.setOnClickListener { onScriptClick(script) }
            btnEdit.setOnClickListener { onScriptClick(script) }
            btnMore.setOnClickListener { onMoreClick(script, it) }
            itemView.setOnLongClickListener {
                onMoreClick(script, btnMore)
                true
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ScriptEntity>() {
            override fun areItemsTheSame(oldItem: ScriptEntity, newItem: ScriptEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ScriptEntity, newItem: ScriptEntity) = oldItem == newItem
        }
    }
}
