package io.github.stopcran.kanji

import android.app.Activity
import android.os.Bundle

/** Debug-only probe for exercising uncaught crash recovery on a disposable emulator. */
class CrashProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("startup", false)) {
            java.io.File(noBackupFilesDir, "crash-probe-startup").writeText("probe")
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        error("Crash report verification")
    }
}
