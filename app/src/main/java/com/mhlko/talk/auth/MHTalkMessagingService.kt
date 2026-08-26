package com.mhlko.talk.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mhlko.talk.MainActivity
import com.mhlko.talk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MHTalkMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onRegistered(installationId: String) {
        registerToken(installationId)
    }

    override fun onNewToken(token: String) {
        registerToken(token)
    }

    private fun registerToken(token: String) {
        scope.launch {
            runCatching {
                SocialRepository.get(applicationContext).registerDeviceToken(token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val inviteId = message.data["inviteId"] ?: return
        val channelId = "mhtalk_invites"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Room invitations", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Invitations from MHTalk friends"
                    enableLights(true)
                    lightColor = Color.rgb(124, 109, 242)
                },
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("mhtalk://invite/$inviteId")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, inviteId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(inviteId.hashCode(), NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(message.notification?.title ?: "MHTalk invitation")
            .setContentText(message.notification?.body ?: "A friend invited you to a room")
            .setAutoCancel(true).setContentIntent(pending).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
}
