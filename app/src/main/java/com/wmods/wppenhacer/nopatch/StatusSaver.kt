package com.wmods.wppenhacer.nopatch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Statuses WhatsApp already downloaded (the ones you opened, or auto-downloaded) live in a
 * hidden ".Statuses" folder. Reading it needs file access, not a patched WhatsApp.
 */
object StatusSaver {

    data class Status(val file: File, val pkg: String, val isVideo: Boolean)

    private const val SAVE_FOLDER = "WaEnhancer"

    private val VIDEO_EXT = setOf("mp4", "3gp", "mkv", "webm")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    private fun folders(): List<Pair<String, File>> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            "com.whatsapp" to File(root, "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
            "com.whatsapp" to File(root, "WhatsApp/Media/.Statuses"),
            "com.whatsapp.w4b" to File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
            "com.whatsapp.w4b" to File(root, "WhatsApp Business/Media/.Statuses"),
        )
    }

    fun list(): List<Status> = folders().flatMap { (pkg, dir) ->
        dir.listFiles().orEmpty().mapNotNull { file ->
            val ext = file.extension.lowercase()
            when {
                !file.isFile || file.length() == 0L -> null
                ext in VIDEO_EXT -> Status(file, pkg, isVideo = true)
                ext in IMAGE_EXT -> Status(file, pkg, isVideo = false)
                else -> null
            }
        }
    }.distinctBy { it.file.absolutePath }.sortedByDescending { it.file.lastModified() }

    fun thumbnail(status: Status, size: Int): Bitmap? = runCatching {
        if (status.isVideo) {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(status.file.absolutePath)
                    val frame = getFrameAtTime(0) ?: return@run null
                    scale(frame, size)
                } finally {
                    release()
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(status.file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeFile(status.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun scale(bitmap: Bitmap, size: Int): Bitmap {
        val ratio = size.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (ratio >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true
        )
    }

    /** Copies a status to Pictures/WaEnhancer or Movies/WaEnhancer. Returns the visible path. */
    fun save(context: Context, status: Status): String {
        val name = status.file.name
        val mime = when (status.file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val baseDir = if (status.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val relative = "$baseDir/$SAVE_FOLDER"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (status.isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out -> status.file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), SAVE_FOLDER).apply { mkdirs() }
            val target = File(dir, name)
            status.file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        }
        return relative
    }
}
