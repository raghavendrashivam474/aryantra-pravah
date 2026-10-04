package com.aryntra.pravah.android.presentation

import android.widget.Button
import android.widget.EditText
import com.aryntra.pravah.android.state.OperationsState

/**
 * Concrete operations button controller (§13).
 * Decouples layout interaction states from flow logic.
 */
class OperationsPanel(
    private val btnStart: Button,
    private val btnStop: Button,
    private val btnDiscover: Button,
    private val btnConnectTcp: Button,
    private val btnConnectBt: Button,
    private val btnSimulateDrop: Button,
    private val etMessage: EditText,
    private val btnSend: Button
) {

    fun render(state: OperationsState) {
        btnStart.isEnabled = state.startEnabled
        btnStop.isEnabled = state.stopEnabled
        
        btnDiscover.text = state.discoveryText
        btnDiscover.isEnabled = state.discoveryEnabled
        
        btnConnectTcp.isEnabled = state.connectTcpEnabled
        btnConnectBt.isEnabled = state.connectBtEnabled
        btnSimulateDrop.isEnabled = state.simulateDropEnabled
        
        etMessage.isEnabled = state.sendEnabled
        btnSend.isEnabled = state.sendEnabled
    }
}
