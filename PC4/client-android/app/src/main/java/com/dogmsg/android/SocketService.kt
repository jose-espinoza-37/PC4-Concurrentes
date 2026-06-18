package com.dogmsg.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder

/**
 * T-B9: Servicio en primer plano que mantiene viva la conexion TCP con el
 * servidor incluso cuando la app pasa a segundo plano, y aloja el [ChatEngine].
 *
 * Las Activities se enlazan (bindService) para obtener el engine a traves del
 * [LocalBinder]. El servicio usa SOLO sockets nativos (via SocketClient).
 *
 * Host/puerto se pueden pasar por Intent extras EXTRA_HOST / EXTRA_PORT; por
 * defecto 10.0.2.2:9000 (10.0.2.2 = localhost del host desde el emulador).
 */
class SocketService : Service() {

    private val binder = LocalBinder()
    private var engine: ChatEngine? = null

    inner class LocalBinder : Binder() {
        fun getEngine(): ChatEngine = engine!!
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine == null) {
            val host = intent?.getStringExtra(EXTRA_HOST) ?: DEFAULT_HOST
            val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
            val filePort = intent?.getIntExtra(EXTRA_FILE_PORT, DEFAULT_FILE_PORT) ?: DEFAULT_FILE_PORT
            val cache = LocalCache(applicationContext)
            engine = ChatEngine(applicationContext, host, port, filePort, cache, "android").also { it.start() }
        }
        startForeground(NOTIF_ID, buildNotification())
        // STICKY: si el sistema mata el servicio, lo recrea para reconectar.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Dog Messenger")
            .setContentText("Conexion activa")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Conexion Dog Messenger",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_FILE_PORT = "file_port"
        const val DEFAULT_HOST = "192.168.1.103"
        const val DEFAULT_PORT = 9000
        const val DEFAULT_FILE_PORT = 9001
        private const val CHANNEL_ID = "dogmsg_conn"
        private const val NOTIF_ID = 1
    }
}