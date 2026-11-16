package com.wmods.wppenhacer.nopatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.activities.base.BaseActivity
import com.wmods.wppenhacer.databinding.ActivityNopatchListBinding
import com.wmods.wppenhacer.databinding.ItemNopatchRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Messages captured from notifications. Reading them here does not open the chat in WhatsApp,
 * so no read receipt (blue ticks) is sent.
 *
 * Three views: list of chats (default), one chat, or only deleted messages.
 */
class MessagesActivity : BaseActivity() {

    private data class Row(
        val title: String,
        val meta: String,
        val body: String,
        val badge: String?,
        val onClick: (() -> Unit)?,
        val onLongClick: (() -> Unit)?,
    )

    private lateinit var binding: ActivityNopatchListBinding
    private val adapter = RowAdapter()
    private lateinit var store: NoPatchStore

    private val chatPkg get() = intent.getStringExtra(EXTRA_PKG)
    private val chatKey get() = intent.getStringExtra(EXTRA_CHAT)
    private val deletedOnly get() = intent.getBooleanExtra(EXTRA_DELETED, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNopatchListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = NoPatchStore.get(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = when {
            chatKey != null -> intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.nopatch_open_messages)
            deletedOnly -> getString(R.string.nopatch_open_deleted)
            else -> getString(R.string.nopatch_open_messages)
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, R.string.nopatch_clear)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_CLEAR) confirmClear()
            true
        }

        binding.list.layoutManager = LinearLayoutManager(this).apply {
            // A conversation reads top to bottom and starts at the newest message.
            stackFromEnd = chatKey != null
        }
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pkg = chatPkg
                val chat = chatKey
                when {
                    pkg != null && chat != null -> store.messages(pkg, chat).map { messageRow(it, showChat = false) }
                    deletedOnly -> store.deletedMessages().map { messageRow(it, showChat = true) }
                    else -> store.chats().map(::chatRow)
                }
            }
            adapter.submit(rows)
            binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            binding.empty.setText(
                if (NoPatch.hasNotificationAccess(this@MessagesActivity)) R.string.nopatch_empty_messages
                else R.string.nopatch_status_off_summary
            )
            if (chatKey != null && rows.isNotEmpty()) binding.list.scrollToPosition(rows.size - 1)
        }
    }

    private fun chatRow(chat: NoPatchStore.Chat): Row {
        val badge = listOfNotNull(
            if (chat.deletedCount > 0) getString(R.string.nopatch_badge_deleted_count, chat.deletedCount) else null,
            if (chat.pkg != packagesDefault) NoPatch.appLabel(chat.pkg) else null,
        ).joinToString(" · ").ifEmpty { null }
        return Row(
            title = chat.chatName,
            meta = relative(chat.lastTimestamp),
            body = chat.lastText,
            badge = badge,
            onClick = { startActivity(chatIntent(this, chat.pkg, chat.chatKey, chat.chatName)) },
            onLongClick = {
                MaterialAlertDialogBuilder(this)
                    .setTitle(chat.chatName)
                    .setMessage(R.string.nopatch_delete_chat)
                    .setPositiveButton(R.string.nopatch_clear) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { store.deleteChat(chat.pkg, chat.chatKey) }
                            load()
                        }
                    }
                    .setNegativeButton(R.string.noroot_cancel, null)
                    .show()
            },
        )
    }

    private fun messageRow(m: NoPatchStore.Message, showChat: Boolean): Row {
        val badges = buildList {
            if (m.deleted) add(getString(R.string.nopatch_badge_deleted))
            if (m.history.isNotEmpty()) add(getString(R.string.nopatch_badge_edited))
        }
        val body = buildString {
            append(m.text)
            m.history.asReversed().forEach { old ->
                append("\n\n").append(getString(R.string.nopatch_before_edit, old))
            }
        }
        return Row(
            title = if (showChat && m.chatName != m.sender) "${m.sender} · ${m.chatName}" else m.sender,
            meta = relative(m.timestamp),
            body = body,
            badge = badges.joinToString(" · ").ifEmpty { null },
            onClick = if (showChat) {
                { startActivity(chatIntent(this, m.pkg, m.chatKey, m.chatName)) }
            } else {
                null
            },
            onLongClick = { copy(m.text) },
        )
    }

    private fun relative(timestamp: Long): String =
        DateUtils.getRelativeDateTimeString(
            this, timestamp, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL
        ).toString()

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, R.string.nopatch_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nopatch_clear)
            .setMessage(R.string.nopatch_clear_confirm)
            .setPositiveButton(R.string.nopatch_clear) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val pkg = chatPkg
                        val chat = chatKey
                        if (pkg != null && chat != null) store.deleteChat(pkg, chat) else store.clear()
                    }
                    load()
                }
            }
            .setNegativeButton(R.string.noroot_cancel, null)
            .show()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemNopatchRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemNopatchRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            with(holder.b) {
                title.text = row.title
                meta.text = row.meta
                body.text = row.body
                badge.text = row.badge.orEmpty()
                badge.visibility = if (row.badge != null) View.VISIBLE else View.GONE
                root.setOnClickListener(row.onClick?.let { action -> View.OnClickListener { action() } })
                root.isClickable = row.onClick != null
                root.setOnLongClickListener(row.onLongClick?.let { action -> View.OnLongClickListener { action(); true } })
            }
        }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DELETED = "deleted"
        private const val MENU_CLEAR = 1

        /** The app label is only shown on chats of the app that is not the main one of this build. */
        private val packagesDefault get() = com.wmods.wppenhacer.noroot.NoRootManager.targetPackage

        fun chatIntent(context: Context, pkg: String, chatKey: String, chatName: String): Intent =
            Intent(context, MessagesActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_CHAT, chatKey)
                .putExtra(EXTRA_NAME, chatName)

        fun deletedIntent(context: Context): Intent =
            Intent(context, MessagesActivity::class.java).putExtra(EXTRA_DELETED, true)
    }
}
