package com.aryntra.pravah.android.presentation

import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic event stream and live terminal wire (§12).
 * Strictly handles event message formatting and autoscroll locks.
 */
class LiveWirePanel(
    private val scrollLog: ScrollView,
    private val tvLog: TextView
) {
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("[$ts] $msg\n")
        scrollLog.post { 
            scrollLog.fullScroll(ScrollView.FOCUS_DOWN) 
        }
    }
    
    fun clear() {
        tvLog.text = ""
    }
}
