package com.wmods.wppenhacer.noroot

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import com.wmods.wppenhacer.App
import com.wmods.wppenhacer.BuildConfig
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import java.io.File
import java.security.KeyStore
import java.util.zip.ZipFile

/**
 * Everything the rootless mode needs besides the patch engine itself: what the target app looks
 * like right now, where its APKs come from, and how the patched result gets installed.
 */
object NoRootManager {

    private const val PREFS = "noroot_state"
    private const val ORIGIN_APK = "assets/lspatch/origin.apk"
    private const val MODULES_DIR = "assets/lspatch/modules/"
    const val ACTION_INSTALL_RESULT = BuildConfig.APPLICATION_ID + ".NOROOT_INSTALL_RESULT"

    /** WhatsApp for the normal build, WhatsApp Business for the business build. */
    val targetPackage: String
        get() = if (App.isOriginalPackage) FeatureLoader.PACKAGE_WPP else FeatureLoader.PACKAGE_BUSINESS

    fun targetLabel(context: Context): String =
        if (App.isOriginalPackage) "WhatsApp" else "WhatsApp Business"

    enum class Status { NOT_INSTALLED, ORIGINAL, PATCHED, OUTDATED, FOREIGN }

    data class TargetState(
        val status: Status,
        val versionName: String?,
        val signatureMatches: Boolean,
        val supported: Boolean
    )

    fun inspect(context: Context): TargetState {
        val pm = context.packageManager
        val info = packageInfo(pm, targetPackage)
            ?: return TargetState(Status.NOT_INSTALLED, null, signatureMatches = true, supported = true)
        val appInfo = info.applicationInfo!!
        val versionName = info.versionName
        val patched = appInfo.metaData?.containsKey("lspatch") == true
        val status = when {
            !patched -> Status.ORIGINAL
            !hasOurModule(appInfo) -> Status.FOREIGN
            isRecordedCurrent(context, info) -> Status.PATCHED
            else -> Status.OUTDATED
        }
        return TargetState(
            status = status,
            versionName = versionName,
            signatureMatches = signatureMatches(context, info),
            supported = isSupported(context, versionName)
        )
    }

    /** True when the target is patched with this module, whatever its build. */
    fun isActive(context: Context): Boolean {
        val state = inspect(context)
        return state.status == Status.PATCHED || state.status == Status.OUTDATED
    }

    fun isSupported(context: Context, versionName: String?): Boolean {
        if (versionName.isNullOrBlank()) return true
        val res = if (App.isOriginalPackage) R.array.supported_versions_wpp else R.array.supported_versions_business
        return context.resources.getStringArray(res).any { versionName.startsWith(it.replace(".xx", "")) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun packageInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        val flags = PackageManager.GET_META_DATA or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
        pm.getPackageInfo(pkg, flags)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun hasOurModule(appInfo: ApplicationInfo): Boolean = runCatching {
        ZipFile(appInfo.sourceDir).use { it.getEntry("$MODULES_DIR${BuildConfig.APPLICATION_ID}.apk") != null }
    }.getOrDefault(false)

    private fun isRecordedCurrent(context: Context, info: PackageInfo): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt("build", -1) == BuildConfig.VERSION_CODE &&
                prefs.getLong("updated", -1) == info.lastUpdateTime
    }

    /** Remembers which module build is inside the installed target. */
    fun recordInstalled(context: Context) {
        val info = packageInfo(context.packageManager, targetPackage) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("build", BuildConfig.VERSION_CODE)
            putLong("updated", info.lastUpdateTime)
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureMatches(context: Context, info: PackageInfo): Boolean = runCatching {
        val keyStore = KeyStore.getInstance("BKS")
        LSPatchEngine.keystoreFile(context).inputStream().use {
            keyStore.load(it, LSPatchEngine.KEYSTORE_PASSWORD.toCharArray())
        }
        val ours = keyStore.getCertificate(LSPatchEngine.KEYSTORE_ALIAS).encoded
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return@runCatching false
        signers.any { it.toByteArray().contentEquals(ours) }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- sources

    /** Prepares the installed target's APKs, unwrapping a previous patch if there is one. */
    fun collectInstalled(context: Context, work: File): List<File> {
        val appInfo = context.packageManager.getApplicationInfo(targetPackage, 0)
        val files = buildList {
            add(File(appInfo.sourceDir))
            appInfo.splitSourceDirs?.forEach { add(File(it)) }
        }
        work.deleteRecursively()
        work.mkdirs()
        return files.map { unwrap(it, File(work, it.name)) }
    }

    /** Copies an APK, APKS, XAPK or APKM picked by the user and extracts its APKs. */
    fun collectFromUri(context: Context, uri: Uri, work: File): List<File> {
        work.deleteRecursively()
        work.mkdirs()
        val input = File(work, "input.bin")
        context.contentResolver.openInputStream(uri)?.use { src ->
            input.outputStream().use { src.copyTo(it) }
        } ?: error(context.getString(R.string.noroot_pick_failed))

        val result = ArrayList<File>()
        ZipFile(input).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") != null) {
                result += unwrap(input, File(work, "base.apk"))
            } else {
                val entries = zip.entries().toList()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                for (entry in entries) {
                    val name = File(entry.name).name
                    val raw = File(work, "raw_$name")
                    zip.getInputStream(entry).use { src -> raw.outputStream().use { src.copyTo(it) } }
                    val finalName = when {
                        name == "base.apk" -> "base.apk"
                        name.startsWith("split_") -> name
                        name == "$targetPackage.apk" -> "base.apk"
                        isBaseApk(context, raw) -> "base.apk"
                        else -> "split_$name"
                    }
                    result += unwrap(raw, File(work, finalName))
                    raw.delete()
                }
            }
        }
        input.delete()

        val base = result.firstOrNull { it.name == "base.apk" }
        val archive = base?.let {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(it.absolutePath, 0)
        }
        if (archive?.packageName != targetPackage) {
            error(context.getString(R.string.noroot_no_base, targetLabel(context)))
        }
        // Base first, splits after: the engine patches the one carrying the application element.
        return result.sortedBy { if (it.name == "base.apk") 0 else 1 }
    }

    private fun isBaseApk(context: Context, apk: File): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        info?.packageName == targetPackage && ZipFile(apk).use { it.getEntry("classes.dex") != null || it.getEntry(ORIGIN_APK) != null }
    }.getOrDefault(false)

    /** If [apk] was already patched, extracts the original nested inside it; otherwise copies it. */
    private fun unwrap(apk: File, dest: File): File {
        ZipFile(apk).use { zip ->
            val origin = zip.getEntry(ORIGIN_APK)
            if (origin != null) {
                zip.getInputStream(origin).use { src -> dest.outputStream().use { src.copyTo(it) } }
                return dest
            }
        }
        if (apk.absolutePath != dest.absolutePath) {
            apk.inputStream().use { src -> dest.outputStream().use { src.copyTo(it) } }
        }
        return dest
    }

    // ---------------------------------------------------------------- install

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installIntentForPermission(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    fun uninstallIntent(): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$targetPackage"))

    /** Streams [apks] into a PackageInstaller session; the result arrives at [InstallResultReceiver]. */
    fun install(context: Context, apks: List<File>, packageName: String, requestCode: Int) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        params.setSize(apks.sumOf { it.length() })
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apks.forEachIndexed { index, apk ->
                apk.inputStream().use { input ->
                    session.openWrite("apk_$index.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .setAction(ACTION_INSTALL_RESULT)
                .putExtra(InstallResultReceiver.EXTRA_TARGET, packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, requestCode, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
