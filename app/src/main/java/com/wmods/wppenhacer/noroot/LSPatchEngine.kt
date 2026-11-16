package com.wmods.wppenhacer.noroot

import android.content.Context
import com.wmods.wppenhacer.BuildConfig
import dalvik.system.DexClassLoader
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException

/**
 * On-device wrapper around the LSPatch patch engine (JingMatrix fork).
 *
 * CI downloads the official `lspatch.jar`, converts it to dex with d8 and ships it as
 * `assets/noroot/engine.jar` (classes.dex + the loader payload under assets/lspatch/).
 * It is loaded in an isolated [DexClassLoader] whose parent is the boot class loader, so its
 * bundled libraries (apksig, gson, jcommander…) never clash with the app's own.
 */
object LSPatchEngine {

    private const val ENGINE_ASSET = "noroot/engine.jar"
    private const val KEYSTORE_ASSET = "noroot/keystore.bks"

    // Credentials of LSPatch's well-known default keystore (same key the LSPatch manager uses).
    const val KEYSTORE_PASSWORD = "123456"
    const val KEYSTORE_ALIAS = "key0"
    const val KEYSTORE_ALIAS_PASSWORD = "123456"

    @Volatile
    private var loader: ClassLoader? = null

    fun isAvailable(context: Context): Boolean = runCatching {
        context.assets.open(ENGINE_ASSET).close()
        true
    }.getOrDefault(false)

    fun keystoreFile(context: Context): File {
        val file = File(context.filesDir, "noroot/keystore.bks")
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "keystore.tmp")
            context.assets.open(KEYSTORE_ASSET).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.renameTo(file)
        }
        return file
    }

    @Synchronized
    private fun classLoader(context: Context): ClassLoader {
        loader?.let { return it }
        val dir = File(context.codeCacheDir, "lspatch-engine")
        val engine = File(dir, "engine-${BuildConfig.VERSION_CODE}.jar")
        if (!engine.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            val tmp = File(dir, "engine.tmp")
            context.assets.open(ENGINE_ASSET).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            check(tmp.renameTo(engine)) { "Cannot prepare patch engine" }
        }
        // Android 14+ refuses to load writable dex files.
        engine.setReadOnly()
        val bootLoader = Context::class.java.classLoader
        return DexClassLoader(engine.absolutePath, null, null, bootLoader).also { loader = it }
    }

    /**
     * Patches [apks] (base + splits) embedding [module] and writes signed APKs into [outDir].
     * Every engine log line is forwarded to [log].
     */
    @Suppress("UNCHECKED_CAST")
    fun patch(
        context: Context,
        apks: List<File>,
        module: File,
        outDir: File,
        log: (String) -> Unit
    ): List<File> {
        val cl = classLoader(context)
        val keystore = keystoreFile(context)
        val args = buildList {
            apks.forEach { add(it.absolutePath) }
            add("--output"); add(outDir.absolutePath)
            add("--force")
            // pm + openat: WhatsApp sees its original signature and reads its original APK.
            add("--sigbypasslv"); add("2")
            add("--embed"); add(module.absolutePath)
            add("--keystore")
            add(keystore.absolutePath); add(KEYSTORE_PASSWORD); add(KEYSTORE_ALIAS); add(KEYSTORE_ALIAS_PASSWORD)
            // Replaces the system_server visibility hook that LSPosed provides with root:
            // lets the patched app reach this app's providers, services and package context.
            add("--add-permission"); add("android.permission.QUERY_ALL_PACKAGES")
            add("--verbose")
        }.toTypedArray()

        val cliClass = cl.loadClass("org.lsposed.patch.LSPatch")
        val loggerBase = cl.loadClass("org.lsposed.patch.util.Logger")
        val specClass = cl.loadClass("org.lsposed.patch.PatchSpec")
        val patcherClass = cl.loadClass("org.lsposed.patch.ApkPatcher")

        val previousOut = System.out
        val previousErr = System.err
        val sink = PrintStream(LineSink(log), true)
        System.setOut(sink)
        System.setErr(sink)
        try {
            val cli = cliClass.getConstructor(Array<String>::class.java).newInstance(args as Any)
            val spec = cliClass.getMethod("toSpec").invoke(cli)
            val logger = cl.loadClass("org.lsposed.patch.util.JavaLogger").getConstructor().newInstance()
            val patcher = patcherClass.getConstructor(loggerBase, specClass).newInstance(logger, spec)
            return patcherClass.getMethod("patch").invoke(patcher) as List<File>
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        } finally {
            sink.flush()
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
    }

    /** Splits whatever the engine prints into lines. */
    private class LineSink(private val onLine: (String) -> Unit) : OutputStream() {
        private val buffer = StringBuilder()

        override fun write(b: Int) {
            if (b == '\n'.code) flushLine() else buffer.append(b.toChar())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val text = String(b, off, len, Charsets.UTF_8)
            for (ch in text) {
                if (ch == '\n') flushLine() else buffer.append(ch)
            }
        }

        override fun flush() {
            if (buffer.isNotEmpty()) flushLine()
        }

        private fun flushLine() {
            val line = buffer.toString().trimEnd('\r')
            buffer.setLength(0)
            if (line.isNotBlank()) runCatching { onLine(line) }
        }
    }
}
