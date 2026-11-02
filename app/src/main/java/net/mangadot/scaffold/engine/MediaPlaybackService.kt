package net.mangadot.scaffold.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import net.mangadot.scaffold.R
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
                override fun onCustomAction(action: String, extras: android.os.Bundle?) { close() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android holds the app to showing a notification, even when it's about to go away again
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_TOGGLE -> current?.let { if (it.isPlaying) it.pause() else it.play() }
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
        val title = media?.webView?.title.orEmpty().ifEmpty { media?.appName.orEmpty() }
        val appName = media?.appName.orEmpty().ifEmpty { "Scraffold" }

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, appName)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .addCustomAction(ACTION_CLOSE, "Close", android.R.drawable.ic_menu_close_clear_cancel)
                .build()
        )

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
            .setContentText(appName)
            .setContentIntent(media?.let { PendingIntent.getActivity(this, 0, it.reopen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) })
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(toggle)
            .addAction(close)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
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

        // A notification left paused shouldn't sit there for good
        private val PAUSED_LIMIT_MS = TimeUnit.MINUTES.toMillis(10)

        // WebView reports on the main thread, and everything here stays on it
        private var current: BackgroundMedia? = null
        private var running: MediaPlaybackService? = null
        private val handler = Handler(Looper.getMainLooper())
        private val closeWhenIdle = Runnable { close() }

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
            running?.stopSelf()
        }
    }
}
