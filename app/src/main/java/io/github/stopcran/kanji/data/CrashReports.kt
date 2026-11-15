package io.github.stopcran.kanji.data

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import io.github.stopcran.kanji.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.Writer
import java.time.Instant
import java.util.UUID

internal data class CrashReport(val id: String, val text: String)

/** Private, bounded reports; no network or automatic sharing. */
internal class CrashReports(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "crash-reports"))

    @Synchronized
    fun record(thread: Thread, error: Throwable): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create crash report directory" }
        val lastStamp = reports().firstOrNull()?.name?.substring(6, 19)?.toLongOrNull() ?: 0L
        val stamp = maxOf(System.currentTimeMillis(), lastStamp + 1)
        val id = "crash-$stamp-${UUID.randomUUID()}.txt"
        val atomic = AtomicFile(File(directory, id))
        val stream = atomic.startWrite()
        var finished = false
        try {
            val writer = LimitedWriter(stream.writer(Charsets.UTF_8), MAX_CHARS)
            val out = PrintWriter(writer)
            try {
                out.println("Time: ${Instant.now()}")
                out.println("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
                out.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                out.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                out.println("Build fingerprint: ${Build.FINGERPRINT}")
                out.println("Thread: ${thread.name}")
                out.println()
                error.printStackTrace(out)
            } catch (_: ReportLimitReached) {
                // Complete the truncated report without traversing the rest of the exception tree.
            }
            writer.finish()
            check(!out.checkError()) { "Could not write crash report" }
            atomic.finishWrite(stream)
            finished = true
        } finally {
            if (!finished) atomic.failWrite(stream)
        }
        reports().drop(MAX_REPORTS).forEach {
            check(it.delete()) { "Could not rotate crash report" }
        }
        return File(directory, id)
    }

    fun latest(): CrashReport? {
        val file = reports().firstOrNull() ?: return null
        check(file.length() <= MAX_BYTES) { "Crash report exceeds the size limit" }
        return CrashReport(file.name, file.readText(Charsets.UTF_8))
    }

    fun unseen(): CrashReport? = latest()?.takeUnless { it.id == seenId() }

    fun dismiss(report: CrashReport) {
        check(report.id.matches(REPORT_NAME)) { "Invalid crash report ID" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create crash report directory" }
        val atomic = AtomicFile(File(directory, "seen"))
        val stream = atomic.startWrite()
        var finished = false
        try {
            stream.write(report.id.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            finished = true
        } finally {
            if (!finished) atomic.failWrite(stream)
        }
    }

    private fun seenId(): String? {
        val file = File(directory, "seen")
        if (!file.exists()) return null
        check(file.length() <= 128) { "Invalid crash report marker" }
        return AtomicFile(file).readFully().toString(Charsets.UTF_8)
    }

    private fun reports(): List<File> {
        if (!directory.exists()) return emptyList()
        return checkNotNull(directory.listFiles()) { "Cannot read crash report directory" }
            .filter { it.isFile && it.name.matches(REPORT_NAME) }.sortedByDescending { it.name }
    }

    companion object {
        const val MAX_REPORTS = 3
        const val MAX_CHARS = 16_000
        const val MAX_BYTES = 64_000
        private val REPORT_NAME = Regex("crash-\\d{13}-[0-9a-f-]{36}\\.txt")
    }
}

private class LimitedWriter(private val delegate: Writer, private var remaining: Int) : Writer() {
    private var truncated = false

    override fun write(chars: CharArray, offset: Int, length: Int) {
        val count = minOf(remaining, length)
        if (count > 0) delegate.write(chars, offset, count)
        remaining -= count
        if (count < length) limitReached()
    }

    override fun write(text: String, offset: Int, length: Int) {
        val count = minOf(remaining, length)
        if (count > 0) delegate.write(text, offset, count)
        remaining -= count
        if (count < length) limitReached()
    }

    private fun limitReached(): Nothing {
        truncated = true
        throw ReportLimitReached()
    }
    fun finish() {
        if (truncated) delegate.write("\n[Report truncated]\n")
        delegate.flush()
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

private class ReportLimitReached : RuntimeException()

internal fun installCrashHandler(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous is CrashHandler) return
    Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context, previous))
}

private class CrashHandler(
    private val context: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    private val writeLock = Any()

    @Suppress("TooGenericExceptionCaught")
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            synchronized(writeLock) {
                val report = try {
                    CrashReports(context).record(thread, error)
                } catch (failure: Throwable) {
                    Log.e("CrashReports", "Unable to save private crash report", failure)
                    null
                }
                if (report != null) {
                    try {
                        publishCrashReport(context, report)
                    } catch (failure: Throwable) {
                        Log.e("CrashReports", "Unable to save crash report in Downloads", failure)
                    }
                }
            }
        } finally {
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}
