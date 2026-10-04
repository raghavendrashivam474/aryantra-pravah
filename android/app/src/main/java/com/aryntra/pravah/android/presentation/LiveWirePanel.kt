package com.aryntra.pravah.android.presentation

import android.widget.TextView
import com.aryntra.pravah.android.state.LiveWireEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A.D2.1: Simplified LiveWirePanel.
 * No longer owns a ScrollView — the outer layout ScrollView handles scrolling.
 */
class LiveWirePanel(
    private val tvLog: TextView
) {
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("[$ts] SYSTEM: $msg\n")
    }

    fun appendEvent(event: LiveWireEvent) {
        val marker = when (event.eventType) {
            "TX" -> "TX"
            "RX" -> "RX"
            "JOIN" -> "JOIN"
            "LEFT" -> "LEFT"
            "PATH" -> "PATH"
            "BUFFER" -> "BUF"
            "ERROR" -> "ERR"
            else -> "SYS"
        }
        tvLog.append("[${event.timestamp}] $marker: ${event.detail}\n")
    }

    fun clear() {
        tvLog.text = ""
    }
}
