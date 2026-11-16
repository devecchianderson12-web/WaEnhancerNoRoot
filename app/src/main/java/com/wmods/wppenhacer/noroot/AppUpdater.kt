package com.wmods.wppenhacer.noroot

import android.content.Context
import com.wmods.wppenhacer.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update from this repository's rolling GitHub release.
 * Every push publishes `WaEnhancerNoRoot-V<build>.apk`; an update is offered only when that
 * build number is higher than the installed one.
 */
object AppUpdater {

    data class Update(val build: Int, val url: String, val notes: String)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val assetPattern = Regex(Regex.escape(BuildConfig.UPDATE_ASSET_PREFIX) + "(\\d+)\\.apk")

    /** Returns the newer build, or null when up to date. Throws on network errors. */
    fun check(): Update? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val release = JSONObject(response.body.string())
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val match = assetPattern.matchEntire(asset.optString("name")) ?: continue
                val build = match.groupValues[1].toInt()
                return if (build > BuildConfig.VERSION_CODE) {
                    Update(build, asset.getString("browser_download_url"), release.optString("body"))
                } else null
            }
        }
        return null
    }

    /** Downloads [update] and hands it to the package installer; the temporary file is removed afterwards. */
    fun downloadAndInstall(context: Context, update: Update, progress: (Int) -> Unit) {
        val file = File(context.cacheDir, "self-update.apk")
        try {
            client.newCall(Request.Builder().url(update.url).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPercent = -1
                        while (input.read(buffer).also { read = it } >= 0) {
                            out.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    progress(percent)
                                }
                            }
                        }
                    }
                }
            }
            NoRootManager.install(context, listOf(file), context.packageName, requestCode = 2)
        } finally {
            file.delete()
        }
    }
}
