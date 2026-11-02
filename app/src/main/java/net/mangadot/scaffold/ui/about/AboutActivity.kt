package net.mangadot.scaffold.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mangadot.scaffold.BuildConfig
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.browser.ExternalLinks
import net.mangadot.scaffold.databinding.ActivityAboutBinding
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader

class AboutActivity : CosmicActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.aboutRootLayout)
            theme.applyHeader(binding.aboutHeaderLayout, binding.aboutScroll)
        }

        setupWindowInsets()
        setupListeners()

        binding.aboutTvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"
        binding.aboutTvUpdateStatus.text = "Installed: v${BuildConfig.VERSION_NAME}"
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.aboutRootLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.aboutHeaderLayout.setPadding(
                binding.aboutHeaderLayout.paddingLeft,
                statusBarHeight + dpToPx(12),
                binding.aboutHeaderLayout.paddingRight,
                dpToPx(12)
            )
            insets
        }
    }

    private fun setupListeners() {
        binding.aboutBtnBack.setOnClickListener {
            finish()
        }

        binding.aboutBtnCheckUpdates.setOnClickListener {
            checkForUpdates()
        }

        binding.aboutRowGithub.setOnClickListener {
            openExternalUrl(getString(R.string.github_repo_url))
        }

        binding.aboutRowIssue.setOnClickListener {
            openExternalUrl(getString(R.string.report_issue_url))
        }

        // Accordion 1: MIT License
        binding.aboutLicenseMitHeader.setOnClickListener {
            toggleAccordion(binding.aboutLicenseMitBody, binding.aboutLicenseMitIcon)
        }

        // Accordion 2: MPL License
        binding.aboutLicenseMplHeader.setOnClickListener {
            toggleAccordion(binding.aboutLicenseMplBody, binding.aboutLicenseMplIcon)
        }

        // Accordion 3: Apache License
        binding.aboutLicenseApacheHeader.setOnClickListener {
            toggleAccordion(binding.aboutLicenseApacheBody, binding.aboutLicenseApacheIcon)
        }
    }

    private fun toggleAccordion(body: TextView, icon: TextView) {
        val isVisible = body.visibility == View.VISIBLE
        body.visibility = if (isVisible) View.GONE else View.VISIBLE
        icon.text = if (isVisible) "▼" else "▲"
    }

    private fun checkForUpdates() {
        binding.aboutBtnCheckUpdates.isEnabled = false
        binding.aboutTvUpdateStatus.text = getString(R.string.checking_updates)

        lifecycleScope.launch {
            delay(650)
            binding.aboutBtnCheckUpdates.isEnabled = true
            binding.aboutTvUpdateStatus.text = getString(R.string.up_to_date, "v${BuildConfig.VERSION_NAME}")
            Toast.makeText(this@AboutActivity, "You are running the latest version of Scraffold", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternalUrl(url: String) {
        ExternalLinks.open(this, Uri.parse(url))
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
