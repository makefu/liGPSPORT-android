package de.syntaxfehler.ligpsport.cli

import android.util.Log

/**
 * Structured RESULT log lines for the adb-driven e2e harness.
 *
 * Format (single line):
 *
 *     RESULT action=<ACTION> req_id=<ID> status=OK|FAIL [key=value …] [reason="quoted text"]
 *
 * The harness greps logcat for `req_id=<ID>` to correlate one
 * broadcast with one result line. Only `reason` is quoted; all other
 * values are bare and must not contain spaces.
 */
object AdbResult {
    const val TAG = "LigpsportAdb"

    enum class Status { OK, FAIL }

    fun emit(
        action: String,
        reqId: String,
        status: Status,
        extra: Map<String, String> = emptyMap(),
        reason: String? = null,
    ) {
        val sb = StringBuilder()
        sb.append("RESULT action=").append(action)
        sb.append(" req_id=").append(reqId)
        sb.append(" status=").append(status.name)
        for ((k, v) in extra) {
            // Skip empty / placeholder values to keep lines clean.
            if (v.isEmpty()) continue
            sb.append(' ').append(k).append('=').append(v.replace(' ', '_'))
        }
        if (reason != null) {
            sb.append(" reason=\"").append(reason.replace('"', '\'')).append('"')
        }
        Log.i(TAG, sb.toString())
    }
}
