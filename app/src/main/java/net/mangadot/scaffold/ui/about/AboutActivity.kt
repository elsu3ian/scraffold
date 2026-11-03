package net.mangadot.scaffold.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.ui.theme.showCosmic
import okhttp3.OkHttpClient
import okhttp3.Request
import net.mangadot.scaffold.BuildConfig
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.browser.ExternalLinks
import net.mangadot.scaffold.databinding.ActivityAboutBinding
import net.mangadot.scaffold.ui.theme.CosmicAccent
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
            theme.applyHeader(binding.aboutHeaderLayout, binding.aboutScroll, overlay = true)
        }

        // The logo is the brand's own color, whatever accent is chosen
        binding.aboutTvLogo.setTextColor(CosmicAccent.DEFAULT)

        setupWindowInsets()
        setupListeners()

        binding.aboutTvUpdateStatus.text = "Installed: ${getString(R.string.release_stage)}"
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

        // Accordion 1: The app's license
        binding.aboutLicenseAppHeader.setOnClickListener {
            toggleAccordion(binding.aboutLicenseAppBody, binding.aboutLicenseAppIcon)
        }

        // Accordion 2: Filter lists
        binding.aboutLicenseFiltersHeader.setOnClickListener {
            toggleAccordion(binding.aboutLicenseFiltersBody, binding.aboutLicenseFiltersIcon)
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

    private sealed class UpdateCheck {
        data class Available(val version: String, val url: String) : UpdateCheck()
        object Current : UpdateCheck()
        object Failed : UpdateCheck()
    }

    /**
     * Builds are published from main rather than as GitHub Releases, so the latest version is the one main's build
     * file declares, and the download is wherever the README's Download APK button points. Both are plain files on
     * raw.githubusercontent.com, which isn't held to the GitHub API's 60 anonymous requests an hour.
     */
    private suspend fun fetchLatestBuild(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val repository = Uri.parse(getString(R.string.github_repo_url)).path.orEmpty().trim('/')
            val raw = "https://raw.githubusercontent.com/$repository/$UPDATE_BRANCH"
            val build = download("$raw/app/build.gradle.kts") ?: return@withContext UpdateCheck.Failed
            val latestCode = versionCode.find(build)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext UpdateCheck.Failed
            if (latestCode <= BuildConfig.VERSION_CODE) return@withContext UpdateCheck.Current
            val latestName = versionName.find(build)?.groupValues?.get(1) ?: latestCode.toString()
            val link = download("$raw/README.md")?.let { downloadButton.find(it)?.groupValues?.get(1) }
                ?: getString(R.string.github_repo_url)
            UpdateCheck.Available(latestName, link)
        } catch (e: Exception) {
            UpdateCheck.Failed
        }
    }

    private fun download(url: String): String? {
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
            return if (response.isSuccessful) response.body?.string() else null
        }
    }

    private fun checkForUpdates() {
        binding.aboutBtnCheckUpdates.isEnabled = false
        binding.aboutTvUpdateStatus.text = getString(R.string.checking_updates)

        lifecycleScope.launch {
            val result = fetchLatestBuild()
            binding.aboutBtnCheckUpdates.isEnabled = true
            val installed = getString(R.string.release_stage)
            when (result) {
                is UpdateCheck.Available -> {
                    binding.aboutTvUpdateStatus.text = "v${result.version} available"
                    AlertDialog.Builder(this@AboutActivity)
                        .setTitle("Update available")
                        .setMessage("Scraffold v${result.version} is out.")
                        .setPositiveButton("Download") { _, _ -> openExternalUrl(result.url) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showCosmic()
                }
                UpdateCheck.Current -> binding.aboutTvUpdateStatus.text = getString(R.string.up_to_date, installed)
                UpdateCheck.Failed -> {
                    binding.aboutTvUpdateStatus.text = "Couldn't check. Installed: $installed"
                    Toast.makeText(this@AboutActivity, "Couldn't check for updates", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openExternalUrl(url: String) {
        ExternalLinks.open(this, Uri.parse(url))
    }

    private companion object {
        const val UPDATE_BRANCH = "main"
        val versionCode = Regex("""\bversionCode\s*=\s*(\d+)""")
        val versionName = Regex("""\bversionName\s*=\s*"([^"]+)"""")
        /** The README's Download APK badge: [![Download APK](badge)](link) */
        val downloadButton = Regex("""\[!\[Download APK]\([^)]*\)]\(([^)\s]+)\)""")
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
