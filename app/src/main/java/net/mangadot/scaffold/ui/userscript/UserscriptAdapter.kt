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

class UserscriptAdapter(
    private val onScriptClick: (ScriptEntity) -> Unit,
    private val onToggleEnabled: (ScriptEntity, Boolean) -> Unit,
    private val onLongClick: (ScriptEntity) -> Unit
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
        private val tvPattern: TextView = itemView.findViewById(R.id.tv_script_pattern)
        private val tvPhase: TextView = itemView.findViewById(R.id.tv_script_phase)
        private val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switch_script_enabled)

        fun bind(script: ScriptEntity) {
            tvName.text = script.name
            tvPattern.text = script.matchPattern
            tvPhase.text = "pre-DOM • ${script.runAt}"

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = script.enabled

            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(script, isChecked)
            }

            itemView.setOnClickListener {
                onScriptClick(script)
            }

            itemView.setOnLongClickListener {
                onLongClick(script)
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
