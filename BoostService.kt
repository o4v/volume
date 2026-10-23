package com.cleanboost

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the audio effects. Staying alive strategy:
 *  - foreground service + ongoing notification (Android won't kill it for memory)
 *  - START_STICKY (system recreates it if it ever is killed)
 *  - stopWithTask=false (swiping the app away from Recents doesn't stop it)
 *  - battery-optimisation exemption (requested from the UI; stops Doze / OEM killers)
 *  - BootReceiver restarts it after reboot or app update
 */
class BoostService : Service() {

    val engine = AudioEngine()
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val clipGuardLoop = object : Runnable {
        override fun run() {
            engine.clipGuardTick()
            handler.postDelayed(this, AudioEngine.TICK_MS)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val s = SettingsStore.load(this)
        engine.update(s)
        if (!s.enabled) stopSelfCleanly() else notifyStatus(s)
    }

    /** Music players announce their audio sessions; we attach per-session as well as globally. */
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            if (id <= 0) return
            when (i.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> engine.attach(id)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> engine.detach(id)
            }
            notifyStatus(SettingsStore.load(c))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val s = SettingsStore.load(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(s), type)

        prefs = SettingsStore.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ContextCompat.registerReceiver(
            this, sessionReceiver,
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            },
            ContextCompat.RECEIVER_EXPORTED,   // must be exported: other apps send these
        )
        engine.update(s)
        handler.post(clipGuardLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SettingsStore.save(this, SettingsStore.load(this).copy(enabled = false))
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_ATTACH -> intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                .takeIf { it > 0 }?.let { engine.attach(it) }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Some OEM ROMs kill the process with the task anyway; ask to be brought straight back.
        if (SettingsStore.load(this).enabled) start(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(clipGuardLoop)
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
        runCatching { unregisterReceiver(sessionReceiver) }
        engine.releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSelfCleanly() {
        handler.removeCallbacks(clipGuardLoop)
        engine.releaseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Boost engine", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(s: BoostSettings): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BoostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("CleanBoost +%.1f dB".format(s.boostDb))
            .setContentText("Clip Guard holding peaks under %.1f dBFS".format(s.ceilingDb))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notifyStatus(s: BoostSettings) {
        if (!s.enabled) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    companion object {
        private const val CHANNEL = "boost"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.cleanboost.STOP"
        const val ACTION_ATTACH = "com.cleanboost.ATTACH"

        @Volatile var instance: BoostService? = null
            private set

        fun start(ctx: Context, attachSession: Int = -1) {
            val i = Intent(ctx, BoostService::class.java)
            if (attachSession > 0) {
                i.action = ACTION_ATTACH
                i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, attachSession)
            }
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
            ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (SettingsStore.load(ctx).enabled) BoostService.start(ctx)
    }
}
