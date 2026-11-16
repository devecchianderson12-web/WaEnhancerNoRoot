package com.wmods.wppenhacer.nopatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.FeatureLoader

/**
 * "No patch" mode: features that work next to the official, untouched WhatsApp, using only
 * regular Android permissions (notification access and file access). WhatsApp keeps updating
 * normally from the Play Store.
 */
object NoPatch {

    val packages = setOf(FeatureLoader.PACKAGE_WPP, FeatureLoader.PACKAGE_BUSINESS)

    private const val PREFS = "nopatch"
    const val KEY_CAPTURE = "capture"
    const val KEY_NOTIFY_DELETED = "notify_deleted"
    const val KEY_NOTIFY_EDITED = "notify_edited"

    private const val CHANNEL_DELETED = "nopatch_deleted"

    /** Messages older than this are dropped so the database does not grow forever. */
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, key: String) = prefs(context).getBoolean(key, true)

    fun setEnabled(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    fun appLabel(pkg: String) = if (pkg == FeatureLoader.PACKAGE_BUSINESS) "WhatsApp Business" else "WhatsApp"

    // --- Notification access ---------------------------------------------------------------

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun notificationAccessIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, WaNotificationListener::class.java).flattenToString()
            )
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // --- File access (statuses) ------------------------------------------------------------

    fun hasFileAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun fileAccessIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    // --- Alerts --------------------------------------------------------------------------------

    fun notifyChange(context: Context, message: NoPatchStore.Message, edited: Boolean, newText: String? = null) {
        if (!canPostNotifications(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DELETED, context.getString(R.string.nopatch_channel_deleted),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val open = PendingIntent.getActivity(
            context, message.chatKey.hashCode(),
            MessagesActivity.chatIntent(context, message.pkg, message.chatKey, message.chatName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (edited) {
            context.getString(R.string.nopatch_alert_edited, message.sender, message.chatName)
        } else {
            context.getString(R.string.nopatch_alert_deleted, message.sender, message.chatName)
        }
        val body = if (edited && newText != null) {
            context.getString(R.string.nopatch_alert_edited_body, message.text, newText)
        } else {
            message.text
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DELETED)
            .setSmallIcon(R.drawable.ic_delete)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(message.timestamp)
            .build()
        runCatching { nm.notify(message.id.toInt(), notification) }
    }
}
