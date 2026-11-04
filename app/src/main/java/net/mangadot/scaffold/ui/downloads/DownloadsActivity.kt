package net.mangadot.scaffold.ui.downloads

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.dao.DownloadDao
import net.mangadot.scaffold.data.db.entity.DownloadEntity
import net.mangadot.scaffold.databinding.ActivityDownloadsBinding
import net.mangadot.scaffold.databinding.ItemDownloadBinding
import net.mangadot.scaffold.engine.AppProfiles
import net.mangadot.scaffold.engine.WebDownloads
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader
import net.mangadot.scaffold.ui.theme.showCosmic
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Everything downloaded in Scraffold, newest first and grouped by day, the way a browser lists it:
 * filters by kind, a search, progress while a file comes in, and opening, sharing or deleting it.
 */
class DownloadsActivity : CosmicActivity() {

    private enum class Kind(val label: String) {
        ALL("All"), VIDEO("Videos"), IMAGE("Images"), AUDIO("Audio"), PDF("PDF"), OTHER("Other");

        companion object {
            fun of(mimeType: String): Kind = when {
                mimeType.startsWith("video/") -> VIDEO
                mimeType.startsWith("image/") -> IMAGE
                mimeType.startsWith("audio/") -> AUDIO
                mimeType == "application/pdf" -> PDF
                else -> OTHER
            }
        }
    }

    /** How far along a download from a link is, as Android's download manager last told it. */
    private data class Live(val received: Long, val total: Long, val waiting: Boolean)

    private sealed class Row {
        data class Day(val title: String) : Row()
        data class File(val download: DownloadEntity, val live: Live?) : Row()
    }

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var downloads: DownloadDao
    private val adapter = Adapter()
    private var all: List<DownloadEntity> = emptyList()
    private val live = mutableMapOf<Long, Live>()
    private var kind = Kind.ALL
    private var search = ""
    private val thumbnails = LruCache<Long, Bitmap>(THUMBNAILS)
    private val noThumbnail = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        downloads = AppDatabase.getInstance(this).downloadDao()
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.downloadsRoot)
            theme.applyHeader(binding.downloadsHeader, binding.downloadsList)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.downloadsRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val header = binding.downloadsHeader
            header.setPadding(header.paddingLeft, bars.top + dp(12), header.paddingRight, dp(12))
            view.setPadding(bars.left, 0, bars.right, 0)
            binding.downloadsList.setPadding(dp(16), 0, dp(16), bars.bottom + dp(24))
            insets
        }

        binding.downloadsList.layoutManager = LinearLayoutManager(this)
        binding.downloadsList.adapter = adapter
        binding.downloadsList.itemAnimator = null
        binding.downloadsBtnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.downloadsBtnSearch.setOnClickListener { if (searching()) closeSearch() else openSearch() }
        binding.downloadsEtSearch.doAfterTextChanged {
            search = it?.toString()?.trim().orEmpty()
            show()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searching()) closeSearch() else finish()
            }
        })

        lifecycleScope.launch {
            downloads.all().collect { list ->
                all = list
                show()
            }
        }
        lifecycleScope.launch { forgetMissing() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    follow()
                    delay(FOLLOW_EVERY)
                }
            }
        }
    }

    private fun searching() = binding.downloadsEtSearch.visibility == View.VISIBLE

    private fun openSearch() {
        binding.downloadsTvTitle.visibility = View.GONE
        binding.downloadsEtSearch.visibility = View.VISIBLE
        binding.downloadsBtnSearch.setImageResource(R.drawable.ic_close)
        binding.downloadsEtSearch.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(binding.downloadsEtSearch, 0)
    }

    private fun closeSearch() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.downloadsEtSearch.windowToken, 0)
        binding.downloadsEtSearch.setText("")
        binding.downloadsEtSearch.visibility = View.GONE
        binding.downloadsTvTitle.visibility = View.VISIBLE
        binding.downloadsBtnSearch.setImageResource(R.drawable.ic_search)
    }

    private fun show() {
        showStorage()
        showFilters()
        val shown = all.filter { download ->
            (kind == Kind.ALL || Kind.of(download.mimeType) == kind) &&
                (search.isEmpty() || download.name.contains(search, ignoreCase = true))
        }
        val rows = mutableListOf<Row>()
        var day: LocalDate? = null
        shown.forEach { download ->
            val date = Instant.ofEpochMilli(download.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
            if (date != day) {
                day = date
                rows += Row.Day(dayTitle(date))
            }
            rows += Row.File(download, live[download.id])
        }
        adapter.submitList(rows)
        binding.downloadsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadsTvEmpty.text = when {
            all.isEmpty() -> "Files you download show up here"
            search.isNotEmpty() -> "No downloads match \"$search\""
            else -> "No ${kind.label.lowercase()} downloaded"
        }
    }

    /** The room downloads take up, against the phone's whole storage. */
    private fun showStorage() {
        val used = all.filter { it.state == DownloadEntity.DONE }.sumOf { it.size }
        val total = runCatching { StatFs(Environment.getExternalStorageDirectory().path).totalBytes }.getOrDefault(0L)
        binding.downloadsTvStorage.text = if (total > 0) {
            "Using ${Formatter.formatFileSize(this, used)} of ${Formatter.formatFileSize(this, total)}"
        } else {
            "Using ${Formatter.formatFileSize(this, used)}"
        }
    }

    /** All, then only the kinds there are files of, so no filter leads to an empty page. */
    private fun showFilters() {
        val present = all.map { Kind.of(it.mimeType) }.toSet()
        val kinds = Kind.values().filter { it == Kind.ALL || it in present }
        if (kind !in kinds) kind = Kind.ALL
        val row = binding.downloadsFilters
        if (row.childCount == kinds.size && kinds.indices.all { (row.getChildAt(it).tag as Kind) == kinds[it] }) {
            kinds.forEachIndexed { index, it -> styleFilter(row.getChildAt(index) as TextView, it == kind) }
            return
        }
        row.removeAllViews()
        kinds.forEach { option ->
            val chip = TextView(this).apply {
                tag = option
                text = option.label
                textSize = 14f
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener {
                    kind = option
                    show()
                    binding.downloadsList.scrollToPosition(0)
                }
            }
            styleFilter(chip, option == kind)
            row.addView(chip, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun styleFilter(chip: TextView, selected: Boolean) {
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (selected) {
                setColor(CosmicAccent.color)
            } else {
                setColor(0)
                setStroke(dp(1), ContextCompat.getColor(this@DownloadsActivity, R.color.border_primary))
            }
        }
        chip.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.text_on_accent else R.color.text_primary)
        )
        chip.isSelected = selected
    }

    private fun dayTitle(date: LocalDate): String {
        val full = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        return when (date) {
            LocalDate.now() -> "Today, $full"
            LocalDate.now().minusDays(1) -> "Yesterday, $full"
            else -> full
        }
    }

    /**
     * Asks the download manager how running downloads are doing, settles the ones that ended, and
     * marks as failed a file a page was writing when Scraffold was closed.
     */
    private suspend fun follow() {
        val running = all.filter { it.state == DownloadEntity.RUNNING }
        if (running.isEmpty()) return
        val changed = withContext(Dispatchers.IO) {
            var changed = false
            running.filter { it.systemId == DownloadEntity.NO_SYSTEM_ID && it.id !in DownloadFiles.writing }.forEach {
                downloads.settle(it.id, DownloadEntity.FAILED, it.written, it.name, null)
            }
            val fromLinks = running.filter { it.systemId != DownloadEntity.NO_SYSTEM_ID }
            if (fromLinks.isEmpty()) return@withContext false
            val found = mutableSetOf<Long>()
            val manager = DownloadFiles.downloadManager(this@DownloadsActivity)
            val query = DownloadManager.Query().setFilterById(*fromLinks.map { it.systemId }.toLongArray())
            runCatching { manager.query(query) }.getOrNull()?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val status = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val received = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val local = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val media = cursor.getColumnIndex(MEDIA_PROVIDER_URI)
                while (cursor.moveToNext()) {
                    val systemId = cursor.getLong(id)
                    val download = fromLinks.firstOrNull { it.systemId == systemId } ?: continue
                    found += systemId
                    when (cursor.getInt(status)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            // The manager adds a number when the name was taken
                            val name = (if (local >= 0) cursor.getString(local) else null)
                                ?.let { Uri.parse(it).lastPathSegment }?.substringAfterLast('/') ?: download.name
                            val mediaUri = if (media >= 0) cursor.getString(media) else null
                            downloads.settle(download.id, DownloadEntity.DONE, cursor.getLong(total), name, mediaUri)
                            changed = true
                        }
                        DownloadManager.STATUS_FAILED -> {
                            downloads.settle(download.id, DownloadEntity.FAILED, 0, download.name, null)
                            changed = true
                        }
                        else -> {
                            val now = Live(
                                cursor.getLong(received), cursor.getLong(total),
                                waiting = cursor.getInt(status) != DownloadManager.STATUS_RUNNING
                            )
                            if (live[download.id] != now) {
                                live[download.id] = now
                                changed = true
                            }
                        }
                    }
                }
            }
            // Gone from the download manager: cancelled from its notification, or cleared from it
            fromLinks.filter { it.systemId !in found }.forEach {
                downloads.delete(it.id)
                changed = true
            }
            changed
        }
        if (changed) show()
    }

    /** Files deleted outside Scraffold leave the list too, as they do from a browser's. */
    private suspend fun forgetMissing() = withContext(Dispatchers.IO) {
        val done = downloads.getAll().filter { it.state == DownloadEntity.DONE }
        val fromLinks = done.filter { it.systemId != DownloadEntity.NO_SYSTEM_ID }
        if (fromLinks.isNotEmpty()) {
            val present = mutableSetOf<Long>()
            val query = DownloadManager.Query().setFilterById(*fromLinks.map { it.systemId }.toLongArray())
            val cursor = runCatching { DownloadFiles.downloadManager(this@DownloadsActivity).query(query) }.getOrNull()
                ?: return@withContext
            cursor.use {
                val id = it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                while (it.moveToNext()) present += it.getLong(id)
            }
            fromLinks.filter { it.systemId !in present }.forEach { downloads.delete(it.id) }
        }
        done.filter { it.systemId == DownloadEntity.NO_SYSTEM_ID && !DownloadFiles.stillThere(this@DownloadsActivity, it) }
            .forEach { downloads.delete(it.id) }
    }

    private fun showOptions(download: DownloadEntity) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (download.state == DownloadEntity.FAILED && URLUtil.isNetworkUrl(download.url)) {
            actions += "Try again" to { tryAgain(download) }
        }
        when (download.state) {
            DownloadEntity.DONE -> {
                actions += "Open" to { DownloadFiles.open(this, download) }
                actions += "Share" to { DownloadFiles.share(this, download) }
            }
            DownloadEntity.RUNNING -> if (download.systemId != DownloadEntity.NO_SYSTEM_ID) {
                actions += "Cancel download" to { lifecycleScope.launch { DownloadFiles.delete(this@DownloadsActivity, download) } }
            }
        }
        if (download.url.isNotEmpty()) actions += "Copy link" to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", download.url))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
        }
        when (download.state) {
            DownloadEntity.DONE -> actions += "Delete" to { confirmDelete(download) }
            DownloadEntity.FAILED -> actions += "Remove from list" to {
                lifecycleScope.launch { DownloadFiles.delete(this@DownloadsActivity, download) }
            }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(download.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .showCosmic()
    }

    /**
     * Starts the download over with the login of the app it came from, as it is now, and lets go of
     * the failed one. A file a page made itself can't be asked for again, so it has no such option.
     */
    private fun tryAgain(download: DownloadEntity) {
        lifecycleScope.launch {
            DownloadFiles.delete(this@DownloadsActivity, download)
            WebDownloads.start(
                this@DownloadsActivity,
                download.url,
                AppProfiles.cookies(this@DownloadsActivity, download.appId),
                fileName = download.name,
                mimeType = download.mimeType.ifEmpty { null },
                appId = download.appId
            )
            binding.downloadsList.scrollToPosition(0)
        }
    }

    private fun confirmDelete(download: DownloadEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${download.name}?")
            .setMessage("The file is removed from your phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    DownloadFiles.delete(this@DownloadsActivity, download)
                    Toast.makeText(this@DownloadsActivity, "Deleted ${download.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .showCosmic()
    }

    private fun detail(download: DownloadEntity, live: Live?): String {
        val site = download.site.takeIf { it.isNotEmpty() }
        fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)
        val status = when (download.state) {
            DownloadEntity.DONE -> download.size.takeIf { it > 0 }?.let(::size)
            DownloadEntity.FAILED -> "Failed"
            else -> {
                val received = live?.received ?: download.written
                val total = live?.total?.takeIf { it > 0 } ?: download.size
                when {
                    live?.waiting == true && received == 0L -> "Waiting to start"
                    live?.waiting == true -> "Paused, ${size(received)} of ${size(total)}"
                    total > 0 -> "${size(received)} of ${size(total)}, ${percent(received, total)}%"
                    received > 0 -> size(received)
                    else -> "Starting"
                }
            }
        }
        return listOfNotNull(status, site).joinToString(" · ")
    }

    private fun percent(received: Long, total: Long) = (received * 100 / total).toInt().coerceIn(0, 100)

    private fun icon(mimeType: String) = when (Kind.of(mimeType)) {
        Kind.VIDEO -> R.drawable.ic_video
        Kind.IMAGE -> R.drawable.ic_image
        Kind.AUDIO -> R.drawable.ic_audio
        else -> R.drawable.ic_file
    }

    /** A picture of the file for photos and videos, read off the screen's thread and kept for a while. */
    private fun loadThumbnail(download: DownloadEntity, target: ItemDownloadBinding) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val kind = Kind.of(download.mimeType)
        if (download.state != DownloadEntity.DONE || (kind != Kind.IMAGE && kind != Kind.VIDEO)) return
        thumbnails.get(download.id)?.let { showThumbnail(target, it); return }
        if (download.id in noThumbnail) return
        val uri = DownloadFiles.mediaUriFor(download) ?: return
        lifecycleScope.launch {
            val picture = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(uri, Size(dp(96), dp(96)), null) }.getOrNull()
            }
            if (picture == null) {
                noThumbnail += download.id
                return@launch
            }
            thumbnails.put(download.id, picture)
            if (target.root.tag == download.id) showThumbnail(target, picture)
        }
    }

    private fun showThumbnail(target: ItemDownloadBinding, picture: Bitmap) {
        target.downloadIvThumb.setImageBitmap(picture)
        target.downloadIvThumb.visibility = View.VISIBLE
        target.downloadIvIcon.visibility = View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class Adapter : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

        override fun getItemViewType(position: Int) = if (getItem(position) is Row.Day) DAY else FILE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == DAY) {
                val title = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(dp(4), dp(20), dp(4), dp(6))
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(parent.context, R.color.text_primary))
                }
                return object : RecyclerView.ViewHolder(title) {}
            }
            val item = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            item.downloadIconFrame.clipToOutline = true
            item.downloadProgress.progressTintList = ColorStateList.valueOf(CosmicAccent.color)
            return FileHolder(item)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Day -> (holder.itemView as TextView).text = row.title
                is Row.File -> (holder as FileHolder).bind(row)
            }
        }
    }

    private inner class FileHolder(private val item: ItemDownloadBinding) : RecyclerView.ViewHolder(item.root) {
        fun bind(row: Row.File) {
            val download = row.download
            item.root.tag = download.id
            item.downloadTvName.text = download.name
            item.downloadTvName.alpha = if (download.state == DownloadEntity.FAILED) 0.6f else 1f
            item.downloadTvDetail.text = detail(download, row.live)
            item.downloadTvDetail.setTextColor(
                ContextCompat.getColor(
                    this@DownloadsActivity,
                    if (download.state == DownloadEntity.FAILED) R.color.accent_rose else R.color.text_secondary
                )
            )
            item.downloadIvIcon.setImageResource(icon(download.mimeType))
            item.downloadIvIcon.visibility = View.VISIBLE
            item.downloadIvThumb.visibility = View.GONE
            item.downloadIvThumb.setImageDrawable(null)
            loadThumbnail(download, item)

            val running = download.state == DownloadEntity.RUNNING
            item.downloadProgress.visibility = if (running) View.VISIBLE else View.GONE
            if (running) {
                val received = row.live?.received ?: download.written
                val total = row.live?.total?.takeIf { it > 0 } ?: download.size
                val known = total > 0 && row.live?.waiting != true
                item.downloadProgress.isIndeterminate = !known
                if (known) item.downloadProgress.progress = (received * 1000 / total).toInt().coerceIn(0, 1000)
            }
            item.root.setOnClickListener {
                when (download.state) {
                    DownloadEntity.DONE -> DownloadFiles.open(this@DownloadsActivity, download)
                    else -> showOptions(download)
                }
            }
            item.downloadBtnMore.setOnClickListener { showOptions(download) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = when {
            old is Row.Day && new is Row.Day -> old.title == new.title
            old is Row.File && new is Row.File -> old.download.id == new.download.id
            else -> false
        }

        override fun areContentsTheSame(old: Row, new: Row) = old == new
    }

    companion object {
        private const val DAY = 0
        private const val FILE = 1
        private const val FOLLOW_EVERY = 800L
        private const val THUMBNAILS = 60
        // Where the download manager put a finished file among the phone's media
        private const val MEDIA_PROVIDER_URI = "mediaprovider_uri"
    }
}
