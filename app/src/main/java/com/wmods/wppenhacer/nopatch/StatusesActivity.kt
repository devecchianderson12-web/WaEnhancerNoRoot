package com.wmods.wppenhacer.nopatch

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.activities.base.BaseActivity
import com.wmods.wppenhacer.databinding.ActivityNopatchListBinding
import com.wmods.wppenhacer.databinding.ItemNopatchStatusBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Statuses WhatsApp already downloaded; tap one to save it to the gallery. */
class StatusesActivity : BaseActivity() {

    private lateinit var binding: ActivityNopatchListBinding
    private val adapter = StatusAdapter()
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var thumbSize = 320

    private val askLegacyRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNopatchListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setTitle(R.string.nopatch_open_statuses)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, R.string.nopatch_refresh)
        binding.toolbar.setOnMenuItemClickListener { load(); true }

        val columns = 3
        thumbSize = resources.displayMetrics.widthPixels / columns
        binding.list.layoutManager = GridLayoutManager(this, columns)
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (!NoPatch.hasFileAccess(this)) {
            adapter.submit(emptyList())
            binding.empty.visibility = View.VISIBLE
            binding.empty.setText(R.string.nopatch_statuses_permission)
            binding.empty.setOnClickListener { requestFileAccess() }
            return
        }
        binding.empty.setOnClickListener(null)
        lifecycleScope.launch {
            val statuses = withContext(Dispatchers.IO) { StatusSaver.list() }
            adapter.submit(statuses)
            binding.empty.visibility = if (statuses.isEmpty()) View.VISIBLE else View.GONE
            binding.empty.setText(R.string.nopatch_statuses_empty)
        }
    }

    private fun requestFileAccess() {
        val intent = NoPatch.fileAccessIntent(this)
        if (intent != null) {
            runCatching { startActivity(intent) }
                .onFailure { toast(getString(R.string.nopatch_open_settings_failed)) }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            askLegacyRead.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun save(status: StatusSaver.Status) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { StatusSaver.save(this@StatusesActivity, status) } }
            result.onSuccess { toast(getString(R.string.nopatch_status_saved, it)) }
                .onFailure { toast(getString(R.string.noroot_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private inner class StatusAdapter : RecyclerView.Adapter<StatusAdapter.Holder>() {
        private var items: List<StatusSaver.Status> = emptyList()

        fun submit(newItems: List<StatusSaver.Status>) {
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemNopatchStatusBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemNopatchStatusBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val status = items[position]
            val key = status.file.absolutePath
            holder.b.play.visibility = if (status.isVideo) View.VISIBLE else View.GONE
            holder.b.thumb.tag = key
            val cached = thumbs.get(key)
            holder.b.thumb.setImageBitmap(cached)
            if (cached == null) {
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) { StatusSaver.thumbnail(status, thumbSize) } ?: return@launch
                    thumbs.put(key, bitmap)
                    if (holder.b.thumb.tag == key) holder.b.thumb.setImageBitmap(bitmap)
                }
            }
            holder.b.root.setOnClickListener {
                MaterialAlertDialogBuilder(this@StatusesActivity)
                    .setTitle(if (status.isVideo) R.string.nopatch_save_video else R.string.nopatch_save_image)
                    .setMessage(NoPatch.appLabel(status.pkg))
                    .setPositiveButton(R.string.nopatch_save) { _, _ -> save(status) }
                    .setNegativeButton(R.string.noroot_cancel, null)
                    .show()
            }
        }
    }

    companion object {
        private const val MENU_REFRESH = 1
    }
}
