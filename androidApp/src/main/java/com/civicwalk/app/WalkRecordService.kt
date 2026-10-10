package com.civicwalk.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground walk recorder. Owns GPS while recording so the walk survives the
 * app going to the background, and shows a persistent notification (the user
 * can turn that off in Settings; then this service is not started).
 */
class WalkRecordService : Service(), LocationListener {

    private var startedAt = 0L
    private var lm: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopUpdates()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startedAt = intent?.getLongExtra(EXTRA_STARTED, System.currentTimeMillis()) ?: System.currentTimeMillis()
        startAsForeground()
        startUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }

    override fun onLocationChanged(loc: Location) {
        sink?.invoke(loc)
    }

    private fun startAsForeground() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            mgr.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(if (startedAt == 0L) System.currentTimeMillis() else startedAt)
            .setShowWhen(true)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startUpdates() {
        if (lm == null) lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, this, Looper.getMainLooper())
        } catch (_: SecurityException) { }
        try {
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this, Looper.getMainLooper())
        } catch (_: Throwable) { }
    }

    private fun stopUpdates() {
        try { lm?.removeUpdates(this) } catch (_: Throwable) { }
        lm = null
    }

    companion object {
        const val ACTION_STOP = "com.civicwalk.app.STOP_WALK"
        private const val EXTRA_STARTED = "startedAt"
        private const val CHANNEL_ID = "walk"
        private const val NOTIF_ID = 41

        @Volatile var sink: ((Location) -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, WalkRecordService::class.java)
            i.putExtra(EXTRA_STARTED, System.currentTimeMillis())
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WalkRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
