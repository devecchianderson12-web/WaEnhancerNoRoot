package com.wmods.wppenhacer.noroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives PackageInstaller session results for the patched app and for self-updates. */
class InstallResultReceiver : BroadcastReceiver() {

    data class Result(val target: String, val status: Int, val message: String?)

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS && target == NoRootManager.targetPackage) {
            NoRootManager.recordInstalled(context)
        }
        val result = Result(target, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        listener?.invoke(result) ?: run { pending = result }
    }

    companion object {
        const val EXTRA_TARGET = "noroot_target"

        @Volatile
        private var listener: ((Result) -> Unit)? = null

        @Volatile
        private var pending: Result? = null

        /** Registers the UI callback; a result that arrived while no screen listened is replayed. */
        fun listen(callback: ((Result) -> Unit)?) {
            listener = callback
            if (callback != null) pending?.let { pending = null; callback(it) }
        }
    }
}
