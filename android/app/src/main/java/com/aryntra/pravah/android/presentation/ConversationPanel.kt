package com.aryntra.pravah.android.presentation

import android.graphics.Color
import android.widget.TextView
import com.aryntra.pravah.android.state.MessageDeliveryStatus
import com.aryntra.pravah.android.state.UiMessageItem

/**
 * A.D3: Human-First Messaging Surface.
 * Section 10: Human-first messaging view with progressive delivery indicators.
 * Section 11: Every message has a journey.
 */
class ConversationPanel(
    private val tvConversation: TextView,
    private val onMessageClick: ((UiMessageItem) -> Unit)? = null
) {
    fun render(messages: List<UiMessageItem>) {
        if (messages.isEmpty()) {
            tvConversation.text = "No messages yet. Send a message below."
            tvConversation.setTextColor(Color.parseColor("#8892b0"))
            return
        }

        val sb = StringBuilder()
        for ((index, msg) in messages.withIndex()) {
            val statusTag = when (msg.status) {
                MessageDeliveryStatus.DELIVERED -> "✓ Delivered"
                MessageDeliveryStatus.DISPATCHED -> "✓ Dispatched"
                MessageDeliveryStatus.PATH_SWITCHING -> "↻ Switching Path"
                MessageDeliveryStatus.BUFFERED -> "⚿ Buffered"
                MessageDeliveryStatus.ACCEPTED -> "◌ Sending..."
                MessageDeliveryStatus.FAILED -> "✗ Failed"
            }

            val prefix = if (msg.isOutgoing) "YOU" else msg.senderDisplayName
            val align = if (msg.isOutgoing) "  " else ""

            sb.append("$align[$prefix · ${msg.timestamp}]\n")
            sb.append("$align  ${msg.content}\n")
            sb.append("$align  $statusTag (tap for journey)\n")
            if (index < messages.size - 1) {
                sb.append("\n")
            }
        }

        tvConversation.text = sb.toString()
        tvConversation.setTextColor(Color.parseColor("#64ffda"))
    }
}