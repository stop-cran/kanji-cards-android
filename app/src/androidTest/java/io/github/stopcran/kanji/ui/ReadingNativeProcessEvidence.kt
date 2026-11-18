package io.github.stopcran.kanji.ui

import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal object ReadingNativeProcessEvidence {
    fun capture(phase: String) {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("readingCaptureToken") ?: return
        require(token.matches(Regex("[0-9a-f]{32}")))
        require(phase in setOf("prepare", "restore", "saved-state-restoration"))
        require(args.getString("readingPhase") == phase)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pid = Process.myPid()
        val evidence = JSONObject()
            .put("phase", phase)
            .put("token", token)
            .put("package", context.packageName)
            .put("pid", pid)
            .put("packageUid", Process.myUid())
            .put("rawStat", File("/proc/$pid/stat").readText())
            .put("rawStatus", File("/proc/$pid/status").readText())
            .put("executable", Os.readlink("/proc/$pid/exe"))
            .put("bootId", File("/proc/sys/kernel/random/boot_id").readText().trim())
            .put("capturedElapsedNanos", SystemClock.elapsedRealtimeNanos())
        val target = File(context.cacheDir, "reading-process-$phase-$token.json")
        val temporary = File(context.cacheDir, "${target.name}.pending")
        check(!target.exists() && temporary.createNewFile())
        FileOutputStream(temporary).use {
            it.write(evidence.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        check(temporary.renameTo(target))
    }
}
