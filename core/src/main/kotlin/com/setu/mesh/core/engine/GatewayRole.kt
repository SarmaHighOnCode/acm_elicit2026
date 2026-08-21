package com.setu.mesh.core.engine

import com.setu.mesh.core.model.MessageId

class GatewayRole(private val node: MeshNode) {
    var uplinkAvailable: Boolean = false
        private set

    private val _delivered = mutableSetOf<MessageId>()
    val delivered: Set<MessageId> get() = _delivered

    fun onUplinkAvailable(available: Boolean) {
        uplinkAvailable = available
    }

    /** Called when the SOS has been handed to the outside world. Emits the RECEIPT. */
    fun acceptDelivery(messageId: MessageId, nowMillis: Long): MessageId? {
        if (!uplinkAvailable) return null
        if (!_delivered.add(messageId)) return null

        return node.originateReceipt(messageId, nowMillis)
    }
}
