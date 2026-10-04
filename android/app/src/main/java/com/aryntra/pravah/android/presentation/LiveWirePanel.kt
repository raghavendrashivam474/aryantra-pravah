package com.aryntra.pravah.android.presentation

import android.widget.ScrollView
import android.widget.TextView
import com.aryntra.pravah.android.state.LiveWireEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A.D2: Upgraded LiveWirePanel.
 * Processes structured domain events with explicit categories.
 * Prevents log scraping and arbitrary dumps (§13).
 */
class LiveWirePanel(
    private val scrollLog: ScrollView,
    private val tvLog: TextView
) {
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("[$ts] SYSTEM: $msg\n")
        scrollToBottom()
    }

    fun appendEvent(event: LiveWireEvent) {
        val categoryMarker = when (event.eventType) {
            "TX" -> "▲ MSG TX"
            "RX" -> "▼ MSG RX"
            "JOIN" -> "◆ PEER JOIN"
            "LEFT" -> "◇ PEER LEFT"
            "PATH" -> "⇄ PATH CHG"
            "BUFFER" -> "⚿ BUFFER"
            "ERROR" -> "❌ ERROR"
            else -> "■ SYSTEM"
        }
        tvLog.append("[${event.timestamp}] $categoryMarker: ${event.detail}\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollLog.post {
            scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    fun clear() {
        tvLog.text = ""
    }
}
