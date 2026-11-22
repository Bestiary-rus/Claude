package dev.bestiary.shashkiai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps self-play training alive while the app is in the background. */
class TrainingService : Service() {
    private val listener = Brain.Listener { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Brain.init(this)
        if (intent?.action == ACTION_STOP) {
            Brain.stopTraining()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Brain.addListener(listener)
        Brain.startTraining()
        return START_NOT_STICKY
    }

    /** Android 15 caps dataSync services; stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Brain.stopTraining()
        stopSelf()
    }

    override fun onDestroy() {
        Brain.removeListener(listener)
        Brain.stopTraining()
        super.onDestroy()
    }

    private fun update() {
        if (!Brain.training) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrainingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val score = Brain.lastScore?.let { " · сила ${(it * 100).toInt()}%" } ?: ""
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ИИ учится")
            .setContentText("Партий: ${Brain.totalGames} (за сеанс ${Brain.gamesThisSession})$score")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    private fun ensureChannel() {
        val nm = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Обучение ИИ", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL = "training"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.bestiary.shashkiai.STOP"

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, TrainingService::class.java)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, TrainingService::class.java).setAction(ACTION_STOP)) }
    }
}
