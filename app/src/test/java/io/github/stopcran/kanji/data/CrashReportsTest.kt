package io.github.stopcran.kanji.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CrashReportsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun recordsDelegatedCrashAndRetainsOnlyBoundedReports() {
        val directory = folder.newFolder("reports")
        val reports = CrashReports(directory)
        repeat(5) { number -> reports.record(Thread.currentThread(), IllegalStateException("failure-$number")) }

        assertEquals(3, directory.listFiles()!!.count { it.name.endsWith(".txt") })
        val last = requireNotNull(reports.latest())
        assertTrue(last.text.contains("failure-4"))
        assertTrue(last.text.contains("App:"))
        assertTrue(last.text.contains("Build fingerprint:"))
        assertEquals(last, reports.unseen())
        reports.dismiss(last)
        assertNull(CrashReports(directory).unseen())
        assertEquals(last, CrashReports(directory).latest())
        reports.record(Thread.currentThread(), IllegalStateException("new crash"))
        assertTrue(requireNotNull(reports.unseen()).text.contains("new crash"))
        reports.dismiss(last)
        assertTrue(requireNotNull(reports.unseen()).text.contains("new crash"))
    }

    @Test
    fun oversizedTraceIsTruncatedWithoutExceedingByteLimit() {
        val directory = folder.newFolder("large")
        val reports = CrashReports(directory)
        reports.record(Thread.currentThread(), IllegalStateException("界".repeat(100_000)))
        val report = requireNotNull(reports.latest())
        assertTrue(report.text.contains("[Report truncated]"))
        assertTrue(File(directory, report.id).length() <= CrashReports.MAX_BYTES)
    }

    @Test
    fun handlerAlwaysDelegatesEvenWhenWritingFails() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = folder.newFile("not-a-directory")
        }
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> delegated = error }
            installCrashHandler(context)
            val failure = IllegalStateException("forced")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)
            assertSame(failure, delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun androidNineDoesNotAttemptPublicExport() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getContentResolver(): ContentResolver = error("Unexpected public export")
        }
        publishCrashReport(context, folder.newFile("report"))
    }

    @Test
    @Config(sdk = [29])
    fun publicExportFailureKeepsPrivateReportAndDelegates() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val directory = folder.newFolder("no-backup")
        var delegated: Throwable? = null
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getContentResolver(): ContentResolver = error("Downloads unavailable")
        }
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> delegated = error }
            installCrashHandler(context)
            val failure = IllegalStateException("public export failure")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)
            assertSame(failure, delegated)
            assertTrue(requireNotNull(CrashReports(context).latest()).text.contains("public export failure"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun concurrentCrashesAllDelegateAndKeepBoundedReports() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val directory = folder.newFolder("concurrent")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val delegated = AtomicInteger()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(5)
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated.incrementAndGet() }
            installCrashHandler(context)
            val handler = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
            val jobs = (1..5).map { number ->
                executor.submit {
                    start.await()
                    handler.uncaughtException(Thread.currentThread(), IllegalStateException("crash-$number"))
                }
            }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(5, delegated.get())
            assertEquals(3, File(directory, "crash-reports").listFiles()!!.count { it.name.endsWith(".txt") })
        } finally {
            executor.shutdownNow()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
