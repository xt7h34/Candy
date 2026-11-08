package com.candy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** What the app knows about itself, so a notification stays quiet when you are already looking at that chat. */
object PushState {
    @Volatile var foreground = false
    @Volatile var openChat: String? = null
}

/** Notifications for new messages. They arrive from the bot through Firebase, and are built here on the phone. */
object Push {
    const val CHANNEL = "messages"
    const val EXTRA_CHAT = "open_chat"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.push_channel_messages), NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }
    }

    /** False when the app was built without google-services.json: everything here then quietly does nothing. */
    fun firebaseReady(ctx: Context): Boolean = try {
        FirebaseApp.getApps(ctx).isNotEmpty()
    } catch (e: Throwable) {
        false
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        } catch (e: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Tells the bot this phone's address, so it can send notifications here. Safe to call any time. */
    suspend fun register(ctx: Context, net: Net): Boolean {
        if (!firebaseReady(ctx)) return false
        val token = currentToken() ?: return false
        return net.post("/candy/push/register", JSONObject().put("token", token)).ok
    }

    /** The phone's own notification settings for this app (where the person can switch them back on). */
    fun openSystemSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // no settings screen to open
        }
    }

    private fun showTest(ctx: Context) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        ensureChannel(ctx)
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.push_test_title))
            .setContentText(ctx.getString(R.string.push_test_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(7, note)
        } catch (e: SecurityException) {
            // notifications are not allowed on this phone
        }
    }

    /** On sign out: this phone stops getting this account's notifications. */
    suspend fun unregister(ctx: Context, net: Net) {
        if (!firebaseReady(ctx)) return
        val token = currentToken() ?: return
        net.post("/candy/push/unregister", JSONObject().put("token", token))
    }

    fun show(ctx: Context, data: Map<String, String>) {
        if (data["type"] == "test") {
            showTest(ctx)
            return
        }
        if (data["type"] != "dm") return
        val hash = bareHash(data["hash"].orEmpty())
        if (hash.isEmpty()) return
        // you are looking at that very chat: no need
        if (PushState.foreground && PushState.openChat == hash) return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        ensureChannel(ctx)
        val name = data["name"].orEmpty().ifBlank { data["hash"].orEmpty() }
        val preview = data["preview"].orEmpty().ifBlank { ctx.getString(R.string.push_new_message) }
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_CHAT, data["hash"].orEmpty())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(ctx, hash.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(hash.hashCode(), note)
        } catch (e: SecurityException) {
            // notifications are not allowed on this phone
        }
    }

    /** Takes down the notification of a chat you just opened. */
    fun cancel(ctx: Context, hash: String) {
        NotificationManagerCompat.from(ctx).cancel(bareHash(hash).hashCode())
    }
}

class CandyMessagingService : FirebaseMessagingService() {
    // Firebase gave this phone a new address: tell the bot, if someone is signed in.
    override fun onNewToken(token: String) {
        val session = SessionStore.load(applicationContext) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            CandyApi.call("/candy/push/register", JSONObject().put("token", token), session.token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Push.show(applicationContext, message.data)
    }
}
