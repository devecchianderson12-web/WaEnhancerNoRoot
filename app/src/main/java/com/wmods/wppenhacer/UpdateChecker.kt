package com.wmods.wppenhacer

import android.app.Activity
import android.content.Intent
import com.wmods.wppenhacer.noroot.AppUpdater
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.utils.YukiLog
import io.noties.markwon.Markwon

/** Runs inside WhatsApp: tells the user when a newer build of this module was released. */
class UpdateChecker(private val mActivity: Activity) : Runnable {

    override fun run() {
        try {
            val update = AppUpdater.check() ?: return
            val key = "V${update.build}"
            if (WppCore.getPrivString("ignored_version", "") == key) return
            mActivity.runOnUiThread { showUpdateDialog(key, update.notes) }
        } catch (e: Exception) {
            YukiLog.log(e)
        }
    }

    private fun showUpdateDialog(version: String, changelog: String) {
        try {
            val markwon = Markwon.create(mActivity)
            val message = buildString {
                append("📦 **Versión:** `").append(version).append("`\n")
                if (changelog.isNotBlank()) append("\n").append(changelog)
                append("\n\nAbrí **WaEnhancer Sin Root**, tocá «Actualizar» y después «Volver a parchear».")
            }
            AlertDialogWpp(mActivity)
                .setTitle("🎉 Nueva versión disponible")
                .setMessage(markwon.toMarkdown(message))
                .setNegativeButton("Ignorar") { dialog, _ ->
                    WppCore.setPrivString("ignored_version", version)
                    dialog.dismiss()
                }
                .setPositiveButton("Actualizar") { dialog, _ ->
                    runCatching {
                        mActivity.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let(mActivity::startActivity)
                    }.onFailure(YukiLog::log)
                    dialog.dismiss()
                }
                .show()
        } catch (e: Exception) {
            YukiLog.log(e)
        }
    }
}
