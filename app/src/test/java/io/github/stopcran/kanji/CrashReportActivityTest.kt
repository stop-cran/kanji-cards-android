package io.github.stopcran.kanji

import android.app.AlertDialog
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import io.github.stopcran.kanji.data.CrashReports
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CrashReportActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.noBackupFilesDir, "crash-reports")

    @Before fun cleanBefore() { directory.deleteRecursively() }
    @After fun cleanAfter() { directory.deleteRecursively() }

    @Test
    fun unseenReportCanBeCopiedSharedAndDismissedWithoutDeletingIt() {
        val reports = CrashReports(context)
        reports.record(Thread.currentThread(), IllegalStateException("startup crash fixture"))
        val saved = requireNotNull(reports.latest())
        val activity = Robolectric.buildActivity(CrashReportActivity::class.java).setup().get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(saved.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(saved.text, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(saved, reports.unseen())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertNull(reports.unseen())
        assertEquals(saved, reports.latest())
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test
    fun settingsCanReopenDismissedReportWithoutLaunchingMain() {
        val reports = CrashReports(context)
        reports.record(Thread.currentThread(), IllegalStateException("already seen"))
        reports.dismiss(requireNotNull(reports.latest()))
        val intent = Intent(context, CrashReportActivity::class.java).putExtra(CrashReportActivity.EXTRA_SHOW_LAST, true)
        val activity = Robolectric.buildActivity(CrashReportActivity::class.java, intent).setup().get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
