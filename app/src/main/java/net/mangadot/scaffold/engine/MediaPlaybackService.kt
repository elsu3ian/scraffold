package net.mangadot.scaffold.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import net.mangadot.scaffold.R
import net.mangadot.scaffold.ui.theme.CosmicAccent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The media notification. Android ends a background app's work unless it has one of these up, and
 * it gives playing media its controls on the notification shade, lock screen and headphones.
 * One page plays at a time, the way one app holds the speakers on a phone.
 */
class MediaPlaybackService : Service() {

    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        running = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Media playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "Scraffold").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { current?.play() }
                override fun onPause() { current?.pause() }
                override fun onStop() { close() }
                override fun onSkipToPrevious() { current?.previous() }
                override fun onSkipToNext() { current?.next() }
                override fun onRewind() { current?.previous() }
                override fun onFastForward() { current?.next() }
                override fun onSeekTo(pos: Long) { current?.seekTo(pos) }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        ACTION_BACK -> current?.previous()
                        ACTION_FORWARD -> current?.next()
                        else -> close()
                    }
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android holds the app to showing a notification, even when it's about to go away again
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_TOGGLE -> current?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_BACK -> current?.previous()
            ACTION_FORWARD -> current?.next()
            ACTION_CLOSE -> close()
        }
        if (current == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = null
        session.release()
        super.onDestroy()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val media = current
        val playing = media?.isPlaying == true
        val appName = media?.appName.orEmpty().ifEmpty { "Scraffold" }
        val title = titleOf(media, appName)
        // Who made it, and in which app: "Some Channel · YouTube", or just the app when the page doesn't say
        val artist = media?.artist.orEmpty().let { if (it.isEmpty() || it.equals(appName, true)) appName else "$it \u00B7 $appName" }
        val cover = cover ?: drawnCover()
        val tracks = media?.hasTracks == true

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, appName)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
                .apply { media?.durationMs?.takeIf { it > 0 }?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
                .build()
        )
        publishState()

        // A site with tracks gets previous and next; anything else jumps 10 seconds back and on
        val back = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, if (tracks) android.R.drawable.ic_media_previous else android.R.drawable.ic_media_rew),
            if (tracks) "Previous" else "Back 10 seconds",
            serviceIntent(ACTION_BACK)
        ).build()
        val forward = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, if (tracks) android.R.drawable.ic_media_next else android.R.drawable.ic_media_ff),
            if (tracks) "Next" else "Forward 10 seconds",
            serviceIntent(ACTION_FORWARD)
        ).build()
        val toggle = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play",
            serviceIntent(ACTION_TOGGLE)
        ).build()
        val close = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Close",
            serviceIntent(ACTION_CLOSE)
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_globe)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(appName)
            .setLargeIcon(cover)
            .setColor(CosmicAccent.color)
            .setContentIntent(media?.let { PendingIntent.getActivity(this, 0, it.reopen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) })
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(back)
            .addAction(toggle)
            .addAction(forward)
            .addAction(close)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    /**
     * Playing or paused, how far along for the seek bar, and which buttons there are. Android's player
     * puts previous and next either side of play, or the first two extra buttons where there are none.
     */
    private fun publishState() {
        val media = current
        val playing = media?.isPlaying == true
        val tracks = media?.hasTracks == true
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (tracks) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT
        if ((media?.durationMs ?: 0L) > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val builder = PlaybackState.Builder().setActions(actions)
        if (media != null && media.positionMs >= 0) {
            builder.setState(state, media.positionMs, if (playing) media.rate else 0f, media.positionAt)
        } else {
            builder.setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        }
        if (!tracks) {
            builder.addCustomAction(ACTION_BACK, "Back 10 seconds", android.R.drawable.ic_media_rew)
            builder.addCustomAction(ACTION_FORWARD, "Forward 10 seconds", android.R.drawable.ic_media_ff)
        }
        builder.addCustomAction(ACTION_CLOSE, "Close", android.R.drawable.ic_menu_close_clear_cancel)
        session.setPlaybackState(builder.build())
    }

    /**
     * What's playing as the page names it, without the site's name tacked on the end ("Song - YouTube"),
     * or the page's title, or the app's name.
     */
    private fun titleOf(media: BackgroundMedia?, appName: String): String {
        val named = media?.title.orEmpty().ifEmpty { media?.webView?.title.orEmpty() }
        // Only the app's or the site's own name goes, so "Artist - Song" keeps its song
        val site = android.net.Uri.parse(media?.webView?.url.orEmpty()).host.orEmpty().lowercase()
        val trimmed = SITE_SUFFIX.find(named)?.takeIf { match ->
            val suffix = match.groupValues[1].trim()
            suffix.equals(appName, true) || (suffix.length >= 3 && site.contains(suffix.lowercase().replace(" ", "")))
        }?.let { named.removeRange(it.range) } ?: named
        return trimmed.trim().ifEmpty { appName }
    }

    // Kept for its color, so the accent cover isn't redrawn on every pause and play
    private var drawn: Pair<Int, Bitmap>? = null

    private fun drawnCover(): Bitmap {
        val color = CosmicAccent.color
        drawn?.takeIf { it.first == color }?.let { return it.second }
        return MediaCover.drawn(color).also { drawn = color to it }
    }

    private fun serviceIntent(action: String): PendingIntent {
        return PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, MediaPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val CHANNEL = "media_playback"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_TOGGLE = "net.mangadot.scaffold.media.TOGGLE"
        private const val ACTION_CLOSE = "net.mangadot.scaffold.media.CLOSE"
        private const val ACTION_BACK = "net.mangadot.scaffold.media.BACK"
        private const val ACTION_FORWARD = "net.mangadot.scaffold.media.FORWARD"
        // " - YouTube", " | Spotify" and the like at the end of a page title
        private val SITE_SUFFIX = Regex("""\s+[-|\u2013\u2014\u00B7]\s+([^-|\u2013\u2014\u00B7]+)$""")

        // A notification left paused shouldn't sit there for good
        private val PAUSED_LIMIT_MS = TimeUnit.MINUTES.toMillis(10)

        // WebView reports on the main thread, and everything here stays on it
        private var current: BackgroundMedia? = null
        private var running: MediaPlaybackService? = null
        private val handler = Handler(Looper.getMainLooper())
        private val closeWhenIdle = Runnable { close() }

        // The page's cover for what's playing now, fetched once per address
        private var cover: Bitmap? = null
        private var coverUrl = ""
        private val fetcher = Executors.newSingleThreadExecutor()

        /** The page says how far along it is, which only the seek bar needs. */
        fun onPosition(media: BackgroundMedia) {
            if (current !== media) return
            running?.publishState()
        }

        /** The page named what's playing, or what it's called changed. */
        fun onMetadata(media: BackgroundMedia) {
            if (current !== media) return
            val url = media.artwork
            if (url != coverUrl) {
                coverUrl = url
                cover = null
                if (url.isNotEmpty()) fetcher.execute {
                    val fetched = MediaCover.fetch(url)
                    handler.post {
                        // Only if nothing newer was asked for meanwhile
                        if (coverUrl == url && fetched != null) {
                            cover = fetched
                            running?.refresh()
                        }
                    }
                }
            }
            running?.refresh()
        }

        fun onChanged(context: Context, media: BackgroundMedia) {
            handler.removeCallbacks(closeWhenIdle)
            when {
                media.isPlaying -> {
                    if (current !== media) {
                        current?.let {
                            it.pause()
                            it.webView.keepRunningHidden = false
                        }
                        current = media
                        // The cover belongs to what was playing before
                        cover = null
                        coverUrl = ""
                        onMetadata(media)
                    }
                    media.webView.keepRunningHidden = true
                    start(context.applicationContext)
                }
                current !== media -> return
                media.webView.isOnScreen -> close()
                else -> {
                    running?.refresh()
                    handler.postDelayed(closeWhenIdle, PAUSED_LIMIT_MS)
                }
            }
        }

        /** The app came back or went away: the title may have moved on, or nothing may be playing now. */
        fun onWindowChanged(media: BackgroundMedia) {
            if (current !== media) return
            if (media.webView.isOnScreen && !media.isPlaying) close() else running?.refresh()
        }

        fun onEnded(media: BackgroundMedia) {
            if (current === media) close()
        }

        private fun start(context: Context) {
            if (running != null) {
                running?.refresh()
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java))
            } catch (e: IllegalStateException) {
                // Android refuses this from the background. The page plays on for as long as the app is left running.
            }
        }

        private fun close() {
            handler.removeCallbacks(closeWhenIdle)
            current?.let {
                it.pause()
                it.webView.keepRunningHidden = false
            }
            current = null
            cover = null
            coverUrl = ""
            running?.stopSelf()
        }
    }
}
