package com.anylisten.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle

/** 页面播放状态（由桥接层从 WebView 页面同步过来） */
data class MediaState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** 页面控制入口：MediaSession/通知栏/音频焦点 产生的指令，由页面层实现（JS 桥） */
interface MediaCommandSink {
    fun onPlayCommand()
    fun onPauseCommand()
    fun onNextCommand()
    fun onPrevCommand()
    fun onSeekCommand(positionMs: Long)
}

/**
 * 前台媒体服务：
 * - 页面开始播放后保持前台，保证后台/锁屏时 WebView 进程不被系统回收，自动切歌事件持续流动；
 * - 通过 MediaSession 提供通知栏/锁屏控制与进度；
 * - 处理音频焦点（来电/其他 App 抢播）；
 * - 播放期间持有部分唤醒锁，避免息屏后 JS/事件停滞。
 */
class MediaService : Service() {

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PREFIX = "com.anylisten.mobile.action."
        const val ACTION_PLAY = ACTION_PREFIX + "play"
        const val ACTION_PAUSE = ACTION_PREFIX + "pause"
        const val ACTION_TOGGLE = ACTION_PREFIX + "toggle"
        const val ACTION_NEXT = ACTION_PREFIX + "next"
        const val ACTION_PREV = ACTION_PREFIX + "prev"
        const val ACTION_STOP = ACTION_PREFIX + "stop"
        const val ACTION_SEEK = ACTION_PREFIX + "seek"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MediaService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaService::class.java))
        }
    }

    private val binder = LocalBinder()

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private var wakelock: PowerManager.WakeLock? = null

    private var state = MediaState()
    private var sink: MediaCommandSink? = null
    private var hasAudioFocus = false
    private var pendingResumeAfterFocusLoss = false

    inner class LocalBinder : Binder() {
        fun getService(): MediaService = this@MediaService
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            sink?.onPlayCommand()
        }

        override fun onPause() {
            sink?.onPauseCommand()
        }

        override fun onSkipToNext() {
            sink?.onNextCommand()
        }

        override fun onSkipToPrevious() {
            sink?.onPrevCommand()
        }

        override fun onSeekTo(pos: Long) {
            sink?.onSeekCommand(pos)
        }

        override fun onStop() {
            sink?.onPauseCommand()
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pendingResumeAfterFocusLoss = false
                sink?.onPauseCommand()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                pendingResumeAfterFocusLoss = state.playing
                sink?.onPauseCommand()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 暂不处理音量闪避，保持简单
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (pendingResumeAfterFocusLoss) {
                    pendingResumeAfterFocusLoss = false
                    sink?.onPlayCommand()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()

        mediaSession = MediaSessionCompat(this, "AnyListenMedia")
        mediaSession.setCallback(sessionCallback)
        mediaSession.isActive = true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> sink?.onPlayCommand()
            ACTION_PAUSE -> sink?.onPauseCommand()
            ACTION_TOGGLE -> {
                if (state.playing) sink?.onPauseCommand() else sink?.onPlayCommand()
            }
            ACTION_NEXT -> sink?.onNextCommand()
            ACTION_PREV -> sink?.onPrevCommand()
            ACTION_STOP -> stopPlayback()
            else -> Unit
        }
        // 兜底：无论以何种方式启动，都立即进入前台（满足前台服务时限要求）
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        sink = null
        releaseWakeLock()
        abandonAudioFocus()
        mediaSession.release()
        super.onDestroy()
    }

    // ---------- 供 PlayerActivity（桥接层）调用 ----------

    /** 页面播放中：开始前台服务并进入播放态 */
    fun attachSink(s: MediaCommandSink?) {
        sink = s
    }

    /** 页面状态同步入口 */
    fun publish(state: MediaState) {
        this.state = state
        mediaSession.isActive = true

        if (state.playing) {
            requestAudioFocus()
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }

        updatePlaybackState()
        updateNotification()
        goForeground()
    }

    /** 播放完全停止 */
    fun stopPlayback() {
        pendingResumeAfterFocusLoss = false
        state = state.copy(playing = false, positionMs = 0L, durationMs = 0L)
        mediaSession.isActive = false
        releaseWakeLock()
        abandonAudioFocus()
        stopForegroundCompat()
    }

    // ---------- 内部 ----------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updatePlaybackState() {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP
        val playState = if (state.playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val ps = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(playState, state.positionMs, 1f)
            .build()
        mediaSession.setPlaybackState(ps)
    }

    private fun buildNotification(): Notification {
        val title = state.title.ifBlank { getString(R.string.app_name) }
        val artist = state.artist.ifBlank { getString(R.string.notification_subtitle) }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_previous,
                getString(R.string.notification_prev),
                commandPendingIntent(ACTION_PREV)
            )
        )
        if (state.playing) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.notification_pause),
                    commandPendingIntent(ACTION_PAUSE)
                )
            )
        } else {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    getString(R.string.notification_play),
                    commandPendingIntent(ACTION_PLAY)
                )
            )
        }
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_media_next,
                getString(R.string.notification_next),
                commandPendingIntent(ACTION_NEXT)
            )
        )
        return builder.build()
    }

    private fun commandPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val result = audioManager.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager.abandonAudioFocus(audioFocusListener)
        hasAudioFocus = false
    }

    private fun acquireWakeLock() {
        if (wakelock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnyListen:media").apply {
                setReferenceCounted(false)
            }
        }
        if (wakelock?.isHeld != true) {
            wakelock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakelock?.isHeld == true) {
            wakelock?.release()
        }
    }
}
