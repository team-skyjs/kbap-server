package com.kbap.common.port.push

data class PushTicket(
    val ok: Boolean,
    val id: String? = null,
    val error: String? = null,
) {
    companion object {
        private const val MAX_ERROR_LENGTH = 255

        fun ok(id: String) = PushTicket(ok = true, id = id)

        fun error(message: String) = PushTicket(ok = false, error = message.take(MAX_ERROR_LENGTH))
    }
}
