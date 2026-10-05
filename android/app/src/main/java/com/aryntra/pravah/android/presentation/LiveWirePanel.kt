package com.aryntra.pravah.android.presentation

import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.aryntra.pravah.android.state.LiveWireEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A.D3-P1: Bounded Bounded Live Wire Panel with Auto-Follow Behavior.
 * Gives Live Wire a fixed vertical viewport and manages auto-scroll vs manual scroll history inspection.
 */
class LiveWirePanel(
    private val tvLog: TextView,
    private val svLog: ScrollView,
    private val tvFollowBadge: TextView? = null
) {
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var isUserScrolledUp = false

    init {
        // Detect manual scroll behavior
        svLog.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val childHeight = svLog.getChildAt(0).height
            val isAtBottom = (childHeight - (svLog.height + scrollY)) <= 30
            
            if (isAtBottom) {
                isUserScrolledUp = false
                tvFollowBadge?.visibility = View.GONE
            } else {
                isUserScrolledUp = true
            }
        }

        // Click badge to re-engage auto-follow
        tvFollowBadge?.setOnClickListener {
            isUserScrolledUp = false
            tvFollowBadge.visibility = View.GONE
            svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun log(msg: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("[$ts] SYS: $msg\n")
        onEventAppended()
    }

    fun appendEvent(event: LiveWireEvent) {
        val marker = when (event.eventType) {
            "TX" -> "▲ TX"
            "RX" -> "▼ RX"
            "JOIN" -> "◆ JOIN"
            "LEFT" -> "◇ LEFT"
            "PATH" -> "⇄ PATH"
            "BUFFER" -> "⚿ BUF"
            "ERROR" -> "⚠ ERR"
            else -> "● SYS"
        }
        tvLog.append("[${event.timestamp}] $marker ${event.detail}\n")
        onEventAppended()
    }

    private fun onEventAppended() {
        svLog.post {
            if (!isUserScrolledUp) {
                svLog.fullScroll(View.FOCUS_DOWN)
            } else {
                tvFollowBadge?.visibility = View.VISIBLE
            }
        }
    }

    fun clear() {
        tvLog.text = ""
        isUserScrolledUp = false
        tvFollowBadge?.visibility = View.GONE
    }
}