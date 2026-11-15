package io.github.stopcran.kanji

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.github.stopcran.kanji.data.CrashReport
import io.github.stopcran.kanji.data.CrashReports
import java.io.IOException

/** Runs without normal application initialization, including when startup repeatedly crashes. */
class CrashReportActivity : Activity() {
    private val reports by lazy { CrashReports(this) }
    private val showLast get() = intent.getBooleanExtra(EXTRA_SHOW_LAST, false)

    @Suppress("TooGenericExceptionCaught")
    override fun attachBaseContext(base: android.content.Context) {
        val localized = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) base else {
                val value = base.getSharedPreferences("settings", MODE_PRIVATE).getString("appLanguage", null)
                val language = when (value) {
                    "ru" -> "ru"
                    "system" -> null
                    else -> "en"
                }
                if (language == null) base else base.createConfigurationContext(
                    android.content.res.Configuration(base.resources.configuration).apply {
                        setLocale(java.util.Locale.forLanguageTag(language))
                    },
                )
            }
        } catch (error: RuntimeException) {
            Log.w("CrashReports", "Unable to apply the saved language", error)
            base
        }
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = try {
            if (showLast) reports.latest() else reports.unseen()
        } catch (error: IOException) {
            Log.e("CrashReports", "Unable to read crash report", error)
            showFailure()
            return
        } catch (error: IllegalStateException) {
            Log.e("CrashReports", "Unable to read crash report", error)
            showFailure()
            return
        }
        if (report != null) showReport(report)
        else if (showLast) {
            AlertDialog.Builder(this).setMessage(R.string.crash_report_none)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.show()
        } else continueToApp()
    }

    private fun showReport(report: CrashReport) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.crash_report_title)
            .setMessage(getString(R.string.crash_report_privacy) + "\n\n" + report.text)
            .setPositiveButton(R.string.crash_report_copy, null)
            .setNeutralButton(R.string.crash_report_share, null)
            .setNegativeButton(R.string.crash_report_dismiss, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_report_title), report.text))
                Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.text)
                try {
                    startActivity(Intent.createChooser(share, getString(R.string.crash_report_share)))
                } catch (error: android.content.ActivityNotFoundException) {
                    Log.w("CrashReports", "No app can share crash reports", error)
                    Toast.makeText(this, R.string.crash_report_share_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                try {
                    reports.dismiss(report)
                    dialog.dismiss()
                    continueToApp()
                } catch (error: IOException) {
                    Log.e("CrashReports", "Unable to dismiss crash report", error)
                    Toast.makeText(this, R.string.crash_report_error, Toast.LENGTH_LONG).show()
                } catch (error: IllegalStateException) {
                    Log.e("CrashReports", "Unable to dismiss crash report", error)
                    Toast.makeText(this, R.string.crash_report_error, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showFailure() {
        AlertDialog.Builder(this)
            .setMessage(R.string.crash_report_error)
            .setPositiveButton(android.R.string.ok) { _, _ -> continueToApp() }
            .setCancelable(false)
            .show()
    }

    private fun continueToApp() {
        if (!showLast) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }

    companion object {
        const val EXTRA_SHOW_LAST = "show_last_crash_report"
    }
}
