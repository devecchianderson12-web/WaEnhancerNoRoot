package com.wmods.wppenhacer.nopatch

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.activities.base.BaseActivity
import com.wmods.wppenhacer.databinding.ActivityNopatchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hub of the "no patch" mode: permissions, switches and entry points. */
class NoPatchActivity : BaseActivity() {

    private lateinit var binding: ActivityNopatchBinding

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNopatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.grantNotifications.setOnClickListener {
            runCatching { startActivity(NoPatch.notificationAccessIntent(this)) }
                .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { toast(getString(R.string.nopatch_open_settings_failed)) }
        }
        binding.grantAlerts.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        bindSwitch(binding.switchCapture, NoPatch.KEY_CAPTURE)
        bindSwitch(binding.switchDeleted, NoPatch.KEY_NOTIFY_DELETED)
        bindSwitch(binding.switchEdited, NoPatch.KEY_NOTIFY_EDITED)

        binding.openMessages.setOnClickListener { startActivity(Intent(this, MessagesActivity::class.java)) }
        binding.openDeleted.setOnClickListener { startActivity(MessagesActivity.deletedIntent(this)) }
        binding.openStatuses.setOnClickListener { startActivity(Intent(this, StatusesActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun bindSwitch(view: com.google.android.material.materialswitch.MaterialSwitch, key: String) {
        view.isChecked = NoPatch.isEnabled(this, key)
        view.setOnCheckedChangeListener { _, checked -> NoPatch.setEnabled(this, key, checked) }
    }

    private fun refresh() {
        val access = NoPatch.hasNotificationAccess(this)
        binding.grantNotifications.visibility = if (access) View.GONE else View.VISIBLE
        binding.grantAlerts.visibility = if (NoPatch.canPostNotifications(this)) View.GONE else View.VISIBLE
        binding.openMessages.isEnabled = access
        binding.openDeleted.isEnabled = access

        if (!access) {
            binding.statusContainer.setBackgroundResource(R.drawable.gradient_warning)
            binding.statusIcon.setImageResource(R.drawable.ic_round_warning_24)
            binding.statusTitle.setText(R.string.nopatch_status_off)
            binding.statusSummary.setText(R.string.nopatch_status_off_summary)
            return
        }
        binding.statusContainer.setBackgroundResource(R.drawable.gradient_success)
        binding.statusIcon.setImageResource(R.drawable.ic_round_check_circle_24)
        binding.statusTitle.setText(R.string.nopatch_status_on)
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { NoPatchStore.get(this@NoPatchActivity).deletedCount() }
            binding.statusSummary.text = getString(R.string.nopatch_status_on_summary, deleted)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
