package com.wmods.wppenhacer.nopatch

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Reads WhatsApp's message notifications, so the official app does not need to be patched.
 *
 * - Every message is saved, so it can be read here without sending the blue ticks.
 * - When WhatsApp updates a chat notification and a message turned into "This message was
 *   deleted" (or vanished from the middle of the conversation), it is marked as deleted and the
 *   saved copy is kept.
 * - When the text of a message changes it is marked as edited and the old text is kept.
 *
 * It only sees what WhatsApp shows in its notifications: muted chats, chats that are open on
 * screen and messages already read on another device are not captured.
 */
class WaNotificationListener : NotificationListenerService() {

    private data class Key(val sender: String, val timestamp: Long)

    /** Last messages shown in each chat notification, to notice which one disappeared. */
    private val snapshots = ConcurrentHashMap<String, List<Key>>()

    private val worker = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        worker.execute { runCatching { NoPatchStore.get(this).prune(NoPatch.RETENTION_MS) } }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NoPatch.packages) return
        if (!NoPatch.isEnabled(this, NoPatch.KEY_CAPTURE)) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        worker.execute {
            try {
                handle(sbn, notification)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read notification", t)
            }
        }
    }

    private fun handle(sbn: StatusBarNotification, notification: Notification) {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) ?: return
        val messages = style.messages.filter { !it.text.isNullOrBlank() }
        if (messages.isEmpty()) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val chatName = (style.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
            ?.toString()?.let(::cleanTitle)
            ?: title?.let(::cleanTitle)
            ?: messages.firstNotNullOfOrNull { it.person?.name?.toString() }
            ?: return
        val chatKey = notification.shortcutId?.takeIf { it.isNotBlank() }
            ?: sbn.tag?.takeIf { it.isNotBlank() }
            ?: chatName
        val pkg = sbn.packageName
        val me = style.user.name?.toString()
        val store = NoPatchStore.get(this)

        val current = ArrayList<Key>(messages.size)
        for (m in messages) {
            val sender = m.person?.name?.toString()?.takeIf { it != me } ?: getString(com.wmods.wppenhacer.R.string.nopatch_me)
            val text = m.text.toString()
            val key = Key(sender, m.timestamp)
            current += key

            if (isDeletedPlaceholder(text)) {
                store.markDeleted(pkg, chatKey, sender, m.timestamp)?.let(::onDeleted)
                continue
            }
            val result = store.save(pkg, chatKey, chatName, sender, text, m.timestamp)
            if (result.change == NoPatchStore.Change.EDITED && result.previous != null &&
                NoPatch.isEnabled(this, NoPatch.KEY_NOTIFY_EDITED)
            ) {
                NoPatch.notifyChange(this, result.previous, edited = true, newText = text)
            }
        }

        // A message that was shown before and is now missing from the middle of the
        // conversation was deleted. Older ones simply scrolled out of the notification.
        val snapshotKey = "$pkg|$chatKey"
        val previous = snapshots.put(snapshotKey, current)
        if (previous != null && current.isNotEmpty()) {
            val oldest = current.minOf { it.timestamp }
            val stillShown = current.toHashSet()
            previous.filter { it !in stillShown && it.timestamp >= oldest }.forEach { gone ->
                store.markDeleted(pkg, chatKey, gone.sender, gone.timestamp)?.let(::onDeleted)
            }
        }
    }

    private fun onDeleted(message: NoPatchStore.Message) {
        if (NoPatch.isEnabled(this, NoPatch.KEY_NOTIFY_DELETED)) {
            NoPatch.notifyChange(this, message, edited = false)
        }
    }

    companion object {
        private const val TAG = "WaeNoPatch"

        /** Text WhatsApp puts instead of a message deleted for everyone, in several languages. */
        private val DELETED_MARKERS = listOf(
            "this message was deleted",
            "se eliminó este mensaje",
            "este mensaje fue eliminado",
            "mensaje eliminado",
            "esta mensagem foi apagada",
            "mensagem apagada",
            "ce message a été supprimé",
            "diese nachricht wurde gelöscht",
            "questo messaggio è stato eliminato",
            "pesan ini telah dihapus",
            "bu mesaj silindi",
            "это сообщение удалено",
            "此消息已删除",
        )

        fun isDeletedPlaceholder(text: String): Boolean {
            val normalized = text.trim().removePrefix("🚫").trim().trimEnd('.', ' ').lowercase()
            return DELETED_MARKERS.any { normalized == it }
        }

        /** "Group (3 messages)" / "Contact: 2 new messages" → "Group" / "Contact". */
        private fun cleanTitle(title: String): String =
            title.replace(Regex("""\s*[(:]\s*\d+\s+\S+.*$"""), "").trim().ifEmpty { title }
    }
}
