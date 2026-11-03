package net.mangadot.scaffold.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import net.mangadot.scaffold.databinding.DialogMyFiltersBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.UserRules
import net.mangadot.scaffold.ui.theme.showCosmic

/** The person's own filters, the element picker's among them, to read and change. */
object MyFilters {

    fun show(activity: Activity) {
        val view = DialogMyFiltersBinding.inflate(activity.layoutInflater)
        val saved = UserRules.load(activity)

        fun count() {
            val rules = view.etMyFilters.text.lineSequence().map { it.trim() }.count { it.isNotEmpty() && !it.startsWith("!") }
            view.tvMyFiltersCount.text = if (rules == 1) "1 filter" else "$rules filters"
        }
        view.etMyFilters.setText(saved)
        view.etMyFilters.doAfterTextChanged { count() }
        count()

        AlertDialog.Builder(activity)
            .setTitle("My filters")
            .setView(view.root)
            .setPositiveButton("Save") { _, _ ->
                val text = view.etMyFilters.text.toString()
                if (text.trim() == saved.trim()) return@setPositiveButton
                UserRules.save(activity, text)
                AdBlockEngine.getInstance(activity).userRulesChanged()
                Toast.makeText(activity, "Saved. Pages use them from their next load.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showCosmic()
    }
}
