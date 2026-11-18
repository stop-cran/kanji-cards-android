package io.github.stopcran.kanji.data

import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Publishes a user-retrievable diagnostic copy without requesting storage permission. */
@Suppress("TooGenericExceptionCaught")
internal fun publishCrashReport(context: Context, report: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val path = "${Environment.DIRECTORY_DOWNLOADS}/Enso Nihongo/Crash reports/"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, "enso-${report.name}")
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, path)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values) ?: throw IOException("Could not create crash report in Downloads")
    try {
        writeReport(resolver, uri, report)
        finishReport(resolver, uri)
    } catch (error: Throwable) {
        try {
            resolver.delete(uri, null, null)
        } catch (cleanup: RuntimeException) {
            android.util.Log.e("CrashReports", "Unable to remove incomplete Downloads report", cleanup)
        }
        throw error
    }
    rotateReports(resolver, collection, path, context.packageName)
}

private fun finishReport(resolver: ContentResolver, uri: Uri) {
    val publish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
    if (resolver.update(uri, publish, null, null) != 1) throw IOException("Could not publish crash report in Downloads")
}

private fun writeReport(resolver: ContentResolver, uri: Uri, report: File) {
    val descriptor = resolver.openFileDescriptor(uri, "w") ?: throw IOException("Could not open crash report in Downloads")
    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
        report.inputStream().use { input -> input.copyTo(output) }
        output.flush()
        output.fd.sync()
    }
}

private fun rotateReports(resolver: ContentResolver, collection: Uri, path: String, packageName: String) {
    val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
    val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?"
    val cursor = resolver.query(
        collection, projection, selection, arrayOf(path, packageName),
        "${MediaStore.Downloads.DATE_ADDED} DESC, ${MediaStore.Downloads._ID} DESC",
    ) ?: throw IOException("Could not inspect crash reports in Downloads")
    cursor.use {
        var kept = 0
        while (it.moveToNext()) {
            val name = it.getString(1)
            if (name == null || !name.matches(Regex("enso-crash-\\d{13}-[0-9a-f-]{36}\\.txt"))) continue
            kept++
            if (kept > CrashReports.MAX_REPORTS &&
                resolver.delete(ContentUris.withAppendedId(collection, it.getLong(0)), null, null) != 1
            ) throw IOException("Could not rotate crash reports in Downloads")
        }
    }
}
