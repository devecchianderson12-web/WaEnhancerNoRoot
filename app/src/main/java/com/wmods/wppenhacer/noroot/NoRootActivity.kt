package com.wmods.wppenhacer.noroot

import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.activities.base.BaseActivity
import com.wmods.wppenhacer.databinding.ActivityNorootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Patches WhatsApp with this module embedded, so every feature works without root or LSPosed. */
class NoRootActivity : BaseActivity() {

    private lateinit var binding: ActivityNorootBinding
    private val logLines = ArrayDeque<String>()
    private var busy = false

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmPatch { startPatch(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNorootBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val label = NoRootManager.targetLabel(this)
        binding.howText.text = getString(R.string.noroot_how_body, label)
        binding.openButton.text = getString(R.string.noroot_open_target, label)

        binding.patchButton.setOnClickListener { confirmPatch { startPatch(null) } }
        binding.pickButton.setOnClickListener {
            if (!ensureInstallPermission()) return@setOnClickListener
            runCatching { pickApk.launch(arrayOf("*/*")) }
                .onFailure { toast(getString(R.string.no_file_picker)) }
        }
        binding.openButton.setOnClickListener {
            packageManager.getLaunchIntentForPackage(NoRootManager.targetPackage)?.let(::startActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        InstallResultReceiver.listen { result -> runOnUiThread { onInstallResult(result) } }
        refresh()
        // Coming back from the uninstall screen: continue with the install that was waiting.
        val waiting = pendingOutputs
        if (!busy && waiting != null && awaitingUninstall &&
            NoRootManager.inspect(this).status == NoRootManager.Status.NOT_INSTALLED
        ) {
            awaitingUninstall = false
            install(waiting)
        }
    }

    override fun onPause() {
        InstallResultReceiver.listen(null)
        super.onPause()
    }

    private fun refresh() {
        val label = NoRootManager.targetLabel(this)
        if (!LSPatchEngine.isAvailable(this)) {
            setStatus(R.drawable.gradient_error, R.drawable.ic_round_error_outline_24,
                getString(R.string.noroot_title), getString(R.string.noroot_engine_missing))
            binding.patchButton.isEnabled = false
            binding.pickButton.isEnabled = false
            return
        }
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { NoRootManager.inspect(this@NoRootActivity) }
            val version = state.versionName.orEmpty()
            when (state.status) {
                NoRootManager.Status.NOT_INSTALLED -> setStatus(R.drawable.gradient_warning,
                    R.drawable.ic_round_warning_24, getString(R.string.noroot_title),
                    getString(R.string.noroot_status_not_installed, label))
                NoRootManager.Status.ORIGINAL -> setStatus(R.drawable.gradient_warning,
                    R.drawable.ic_round_warning_24, getString(R.string.noroot_card_title),
                    getString(R.string.noroot_status_original, label, version))
                NoRootManager.Status.PATCHED -> setStatus(R.drawable.gradient_success,
                    R.drawable.ic_round_check_circle_24, getString(R.string.module_enabled),
                    getString(R.string.noroot_status_patched, label, version))
                NoRootManager.Status.OUTDATED -> setStatus(R.drawable.gradient_update,
                    R.drawable.ic_round_update_24, getString(R.string.noroot_repatch),
                    getString(R.string.noroot_status_outdated, label, version))
                NoRootManager.Status.FOREIGN -> setStatus(R.drawable.gradient_warning,
                    R.drawable.ic_round_warning_24, getString(R.string.noroot_card_title),
                    getString(R.string.noroot_status_foreign, label, version))
            }
            val installed = state.status != NoRootManager.Status.NOT_INSTALLED
            binding.patchButton.isEnabled = installed && !busy
            binding.pickButton.isEnabled = !busy
            binding.patchButton.setText(
                if (state.status == NoRootManager.Status.ORIGINAL) R.string.noroot_patch else R.string.noroot_repatch
            )
            binding.openButton.visibility =
                if (state.status == NoRootManager.Status.PATCHED || state.status == NoRootManager.Status.OUTDATED) View.VISIBLE else View.GONE
            if (installed && !state.supported) {
                binding.warningText.text = getString(R.string.noroot_unsupported_version, version)
                binding.warningText.visibility = View.VISIBLE
            } else {
                binding.warningText.visibility = View.GONE
            }
        }
    }

    private fun setStatus(background: Int, icon: Int, title: String, summary: String) {
        binding.statusContainer.setBackgroundResource(background)
        binding.statusIcon.setImageResource(icon)
        binding.statusTitle.text = title
        binding.statusSummary.text = summary
    }

    private fun ensureInstallPermission(): Boolean {
        if (NoRootManager.canInstall(this)) return true
        toast(getString(R.string.noroot_install_permission))
        runCatching { startActivity(NoRootManager.installIntentForPermission(this)) }
        return false
    }

    private fun startPatch(source: Uri?) {
        if (busy || !ensureInstallPermission()) return
        setBusy(true, getString(R.string.noroot_working))
        logLines.clear()
        binding.logText.text = ""
        binding.logCard.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val outputs = withContext(Dispatchers.IO) {
                    val root = File(cacheDir, "noroot")
                    val input = File(root, "in")
                    val output = File(root, "out").apply { deleteRecursively(); mkdirs() }
                    val apks = if (source != null) {
                        NoRootManager.collectFromUri(this@NoRootActivity, source, input)
                    } else {
                        NoRootManager.collectInstalled(this@NoRootActivity, input)
                    }
                    val module = File(applicationInfo.sourceDir)
                    val produced = LSPatchEngine.patch(this@NoRootActivity, apks, module, output) { line ->
                        runOnUiThread { appendLog(line) }
                    }
                    input.deleteRecursively()
                    produced
                }
                pendingOutputs = outputs
                val state = withContext(Dispatchers.IO) { NoRootManager.inspect(this@NoRootActivity) }
                if (state.status != NoRootManager.Status.NOT_INSTALLED && !state.signatureMatches) {
                    setBusy(false, null)
                    askUninstall()
                } else {
                    install(outputs)
                }
            } catch (t: Throwable) {
                appendLog(android.util.Log.getStackTraceString(t))
                fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun install(outputs: List<File>) {
        setBusy(true, getString(R.string.noroot_installing))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NoRootManager.install(this@NoRootActivity, outputs, NoRootManager.targetPackage, requestCode = 1)
                }
            } catch (t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun onInstallResult(result: InstallResultReceiver.Result) {
        if (result.target != NoRootManager.targetPackage) return
        when (result.status) {
            PackageInstaller.STATUS_SUCCESS -> {
                pendingOutputs = null
                File(cacheDir, "noroot").deleteRecursively()
                setBusy(false, getString(R.string.noroot_done, NoRootManager.targetLabel(this)))
                refresh()
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                setBusy(false, null)
                askUninstall()
            }

            else -> fail(result.message ?: "status ${result.status}")
        }
    }

    /**
     * Patching re-signs WhatsApp: it may refuse to register the account and Google Drive
     * backups can't be restored. Warn before the first install, when the official one is there.
     */
    private fun confirmPatch(action: () -> Unit) {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { NoRootManager.inspect(this@NoRootActivity) }
            if (state.status != NoRootManager.Status.ORIGINAL && state.status != NoRootManager.Status.NOT_INSTALLED) {
                action()
                return@launch
            }
            MaterialAlertDialogBuilder(this@NoRootActivity)
                .setTitle(R.string.noroot_risk_title)
                .setMessage(getString(R.string.noroot_risk_msg, NoRootManager.targetLabel(this@NoRootActivity)))
                .setPositiveButton(R.string.noroot_risk_nopatch) { _, _ ->
                    startActivity(android.content.Intent(this@NoRootActivity, com.wmods.wppenhacer.nopatch.NoPatchActivity::class.java))
                    finish()
                }
                .setNeutralButton(R.string.noroot_risk_continue) { _, _ -> action() }
                .setNegativeButton(R.string.noroot_cancel, null)
                .show()
        }
    }

    private fun askUninstall() {
        val label = NoRootManager.targetLabel(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.noroot_uninstall_title, label))
            .setMessage(getString(R.string.noroot_uninstall_msg, label))
            .setPositiveButton(R.string.noroot_uninstall) { _, _ ->
                awaitingUninstall = true
                runCatching { startActivity(NoRootManager.uninstallIntent()) }
            }
            .setNeutralButton(R.string.noroot_backup_first) { _, _ ->
                packageManager.getLaunchIntentForPackage(NoRootManager.targetPackage)?.let(::startActivity)
            }
            .setNegativeButton(R.string.noroot_cancel, null)
            .show()
    }

    private fun fail(message: String) {
        setBusy(false, getString(R.string.noroot_failed, message))
        refresh()
    }

    private fun setBusy(value: Boolean, text: String?) {
        busy = value
        binding.patchButton.isEnabled = !value
        binding.pickButton.isEnabled = !value
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        binding.progressBox.visibility = if (value || text != null) View.VISIBLE else View.GONE
        binding.progressText.text = text.orEmpty()
    }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        binding.logText.text = logLines.joinToString("\n")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val MAX_LOG_LINES = 150

        /** Patched APKs waiting for the user to uninstall the incompatible original. */
        @Volatile
        private var pendingOutputs: List<File>? = null

        @Volatile
        private var awaitingUninstall = false
    }
}
