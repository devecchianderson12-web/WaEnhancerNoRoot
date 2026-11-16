package com.wmods.wppenhacer.nopatch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Messages captured from WhatsApp's notifications (no patch mode).
 *
 * A message is identified by app + chat + sender + timestamp, which WhatsApp keeps stable
 * every time it reposts the notification of a chat.
 */
class NoPatchStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "nopatch_messages.db", null, 1) {

    data class Message(
        val id: Long,
        val pkg: String,
        val chatKey: String,
        val chatName: String,
        val sender: String,
        val text: String,
        val timestamp: Long,
        val deleted: Boolean,
        /** Previous texts of an edited message, newest last. */
        val history: List<String>,
    )

    data class Chat(
        val pkg: String,
        val chatKey: String,
        val chatName: String,
        val lastText: String,
        val lastTimestamp: Long,
        val count: Int,
        val deletedCount: Int,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                chat_key TEXT NOT NULL,
                chat_name TEXT NOT NULL,
                sender TEXT NOT NULL,
                text TEXT NOT NULL,
                ts INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                history TEXT NOT NULL DEFAULT '',
                UNIQUE(pkg, chat_key, sender, ts)
            )"""
        )
        db.execSQL("CREATE INDEX idx_messages_chat ON messages(pkg, chat_key, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun find(pkg: String, chatKey: String, sender: String, timestamp: Long): Message? =
        readableDatabase.query(
            "messages", null, "pkg=? AND chat_key=? AND sender=? AND ts=?",
            arrayOf(pkg, chatKey, sender, timestamp.toString()), null, null, null
        ).use { c -> if (c.moveToFirst()) c.toMessage() else null }

    /** What changed when a message was saved. */
    enum class Change { NEW, SAME, EDITED }

    /** [previous] is the stored message before this save (null for a new one). */
    data class SaveResult(val change: Change, val previous: Message?)

    @Synchronized
    fun save(pkg: String, chatKey: String, chatName: String, sender: String, text: String, timestamp: Long): SaveResult {
        val existing = find(pkg, chatKey, sender, timestamp)
        val db = writableDatabase
        if (existing == null) {
            db.insertWithOnConflict("messages", null, ContentValues().apply {
                put("pkg", pkg)
                put("chat_key", chatKey)
                put("chat_name", chatName)
                put("sender", sender)
                put("text", text)
                put("ts", timestamp)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            return SaveResult(Change.NEW, null)
        }
        val values = ContentValues().apply { put("chat_name", chatName) }
        val change = if (existing.text != text && !existing.deleted) {
            values.put("text", text)
            values.put("history", (existing.history + existing.text).joinToString(HISTORY_SEPARATOR))
            Change.EDITED
        } else {
            Change.SAME
        }
        db.update("messages", values, "id=?", arrayOf(existing.id.toString()))
        return SaveResult(change, existing)
    }

    /** Marks a message as deleted. Returns it only if it was not already marked. */
    @Synchronized
    fun markDeleted(pkg: String, chatKey: String, sender: String, timestamp: Long): Message? {
        val existing = find(pkg, chatKey, sender, timestamp) ?: return null
        if (existing.deleted) return null
        writableDatabase.update(
            "messages", ContentValues().apply { put("deleted", 1) },
            "id=?", arrayOf(existing.id.toString())
        )
        return existing.copy(deleted = true)
    }

    fun chats(): List<Chat> = readableDatabase.rawQuery(
        """SELECT pkg, chat_key,
                  (SELECT chat_name FROM messages m2 WHERE m2.pkg = m.pkg AND m2.chat_key = m.chat_key ORDER BY ts DESC LIMIT 1),
                  (SELECT text FROM messages m3 WHERE m3.pkg = m.pkg AND m3.chat_key = m.chat_key ORDER BY ts DESC LIMIT 1),
                  MAX(ts), COUNT(*), SUM(deleted)
           FROM messages m GROUP BY pkg, chat_key ORDER BY MAX(ts) DESC""",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Chat(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5), c.getInt(6)))
            }
        }
    }

    fun messages(pkg: String, chatKey: String): List<Message> = readableDatabase.query(
        "messages", null, "pkg=? AND chat_key=?", arrayOf(pkg, chatKey), null, null, "ts ASC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun deletedMessages(): List<Message> = readableDatabase.query(
        "messages", null, "deleted=1", null, null, null, "ts DESC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun deletedCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE deleted=1", null)
        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun deleteChat(pkg: String, chatKey: String) {
        writableDatabase.delete("messages", "pkg=? AND chat_key=?", arrayOf(pkg, chatKey))
    }

    fun clear() {
        writableDatabase.delete("messages", null, null)
    }

    /** Keeps the database small: drops messages older than [maxAgeMs]. */
    fun prune(maxAgeMs: Long) {
        writableDatabase.delete(
            "messages", "ts < ?", arrayOf((System.currentTimeMillis() - maxAgeMs).toString())
        )
    }

    private fun android.database.Cursor.toMessage() = Message(
        id = getLong(getColumnIndexOrThrow("id")),
        pkg = getString(getColumnIndexOrThrow("pkg")),
        chatKey = getString(getColumnIndexOrThrow("chat_key")),
        chatName = getString(getColumnIndexOrThrow("chat_name")),
        sender = getString(getColumnIndexOrThrow("sender")),
        text = getString(getColumnIndexOrThrow("text")),
        timestamp = getLong(getColumnIndexOrThrow("ts")),
        deleted = getInt(getColumnIndexOrThrow("deleted")) != 0,
        history = getString(getColumnIndexOrThrow("history"))
            .split(HISTORY_SEPARATOR).filter { it.isNotEmpty() },
    )

    companion object {
        private const val HISTORY_SEPARATOR = "\u001E"

        @Volatile
        private var instance: NoPatchStore? = null

        fun get(context: Context): NoPatchStore =
            instance ?: synchronized(this) { instance ?: NoPatchStore(context).also { instance = it } }
    }
}
