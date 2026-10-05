package com.aryntra.pravah.android.presentation

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aryntra.pravah.android.state.UiMessageItem

/**
 * A.D3 Progressive Technical Disclosure Dialog.
 * Brief Sections 11 & 12:
 * Level 1 - Human
 * Level 2 - Network-aware
 * Level 3 - Technical lifecycle steps
 * Level 4 - Forensic depth & wire event log
 */
object MessageJourneyDialog {

    fun show(context: Context, item: UiMessageItem) {
        val journey = item.journey

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor("#12121e"))
        }

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- LEVEL 1: HUMAN SURFACE ---
        val tvHumanHeader = TextView(context).apply {
            text = "MESSAGE JOURNEY"
            textSize = 16f
            setTextColor(Color.parseColor("#00ff88"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        contentLayout.addView(tvHumanHeader)

        val tvHumanStatus = TextView(context).apply {
            text = "Status: ${journey.humanStatus}\nContent: \"${item.content}\""
            textSize = 13f
            setTextColor(Color.parseColor("#e0e0e0"))
            setPadding(0, 0, 0, 16)
        }
        contentLayout.addView(tvHumanStatus)

        // --- LEVEL 2: NETWORK-AWARE SUMMARY ---
        val tvLevel2Header = TextView(context).apply {
            text = "-- NETWORK-AWARE SUMMARY --"
            textSize = 11f
            setTextColor(Color.parseColor("#00e5ff"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }
        contentLayout.addView(tvLevel2Header)

        val tvLevel2Content = TextView(context).apply {
            text = "${journey.networkAwareStatus}\n${journey.technicalSummary}"
            textSize = 12f
            setTextColor(Color.parseColor("#80d8ff"))
            setPadding(0, 0, 0, 16)
        }
        contentLayout.addView(tvLevel2Content)

        // --- LEVEL 3: TECHNICAL LIFECYCLE STEPS ---
        val tvLevel3Header = TextView(context).apply {
            text = "-- LIFECYCLE STEPS --"
            textSize = 11f
            setTextColor(Color.parseColor("#ffb74d"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }
        contentLayout.addView(tvLevel3Header)

        val sbSteps = StringBuilder()
        if (journey.steps.isEmpty()) {
            sbSteps.append("  (No intermediate steps recorded)\n")
        } else {
            for (step in journey.steps) {
                val mark = if (step.isCompleted) "✓" else (if (step.isCurrent) "◌" else "○")
                sbSteps.append("  $mark ${step.title.padEnd(12)} [${step.timestamp}]\n")
                sbSteps.append("    ${step.description}\n")
            }
        }

        val tvLevel3Content = TextView(context).apply {
            text = sbSteps.toString()
            textSize = 11f
            setTextColor(Color.parseColor("#fff3e0"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 16)
        }
        contentLayout.addView(tvLevel3Content)

        // --- LEVEL 4: FORENSIC DEPTH ---
        val tvLevel4Header = TextView(context).apply {
            text = "-- FORENSIC TRACE --"
            textSize = 11f
            setTextColor(Color.parseColor("#e040fb"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }
        contentLayout.addView(tvLevel4Header)

        val sbForensic = StringBuilder()
        sbForensic.append("Message ID : ${journey.messageId}\n")
        sbForensic.append("Sequence   : ${journey.sequenceNumber}\n")
        sbForensic.append("Destination: ${journey.destinationPeerId}\n")
        sbForensic.append("Sender     : ${item.senderPeerId}\n\n")

        if (journey.forensicLog.isNotEmpty()) {
            sbForensic.append("Wire & State Events:\n")
            for (line in journey.forensicLog) {
                sbForensic.append("  $line\n")
            }
        }

        val tvLevel4Content = TextView(context).apply {
            text = sbForensic.toString()
            textSize = 10f
            setTextColor(Color.parseColor("#ea80fc"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 8)
        }
        contentLayout.addView(tvLevel4Content)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        AlertDialog.Builder(context)
            .setView(rootLayout)
            .setPositiveButton("Close", null)
            .show()
    }
}