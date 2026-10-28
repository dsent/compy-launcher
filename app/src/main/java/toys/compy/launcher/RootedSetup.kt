/*
 * Copyright (c) 2026 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */
package toys.compy.launcher

import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit

/** The native carrier publishes only its intent and dedicated setup control. */
object RootedSetup {
    enum class State { READY, WAITING, FAILED }
    private val token = Regex("[0-9a-f]{32}")
    private fun validToken(value: String): Boolean = token.matches(value) && value.any { it != '0' }

    internal fun state(carrier: String, intent: String, control: String): State {
        if (carrier.isEmpty()) return State.READY
        if (carrier != "1") return State.FAILED
        if (intent.isEmpty()) return State.WAITING
        if (!validToken(intent)) return State.FAILED
        if (control == "c:$intent") return State.READY
        val parts = control.split(':')
        return if (parts.size == 3 && parts[0] == "p" && parts[1] == intent && validToken(parts[2])) {
            State.WAITING
        } else State.FAILED
    }

    /** Called on the startup executor; it never runs an installer or reads a baseline. */
    fun read(): State {
        // The native provisioning carrier is supplied by the Android 13 image.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return State.READY
        return try {
            val process = ProcessBuilder(
                "/system/bin/sh", "-c",
                "getprop ro.compy.setup_gate; getprop sys.compy.setup.intent; getprop persist.sys.compy.setup",
            ).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    State.FAILED
                } else {
                    val lines = process.inputStream.bufferedReader().use { it.readLines() }
                    if (process.exitValue() != 0 || lines.size != 3) State.FAILED
                    else state(lines[0], lines[1], lines[2])
                }
            } finally {
                process.destroy()
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.e("RootedSetup", "Could not read setup control", error)
            State.FAILED
        }
    }
}
