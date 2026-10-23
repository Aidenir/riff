package com.aidenir.riff.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.aidenir.riff.R

/**
 * Keeps Riff running while the user logs in in the browser. Spotify's page
 * redirects to a server inside Riff (127.0.0.1:8898), and some phones
 * (Motorola's freezer, for one) freeze an app seconds after it leaves the
 * screen, so the redirect would hang. A short foreground service prevents
 * that; it stops as soon as the login ends.
 */
class LoginService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.login_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.finish_login_in_browser))
            .setOngoing(true)
            .build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "login"
        private const val ID = 2001

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LoginService::class.java))
            } catch (e: Exception) {
                Log.w("RiffLogin", "Could not keep Riff awake for the login", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoginService::class.java))
        }
    }
}
