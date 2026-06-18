package com.dogmsg.android.protocol

/**
 * Opcodes del protocolo Dog Messenger (Seccion 3).
 *
 * Los valores numericos son IDENTICOS a los de Java/Python: forman parte del
 * contrato compartido. No cambiar.
 */
enum class OpCode(val code: Int) {
    AUTH_REQUEST(0x01),
    AUTH_RESPONSE(0x02),

    MSG_TEXT(0x10),
    MSG_IMAGE(0x11),
    MSG_FILE(0x12),
    MSG_ACK(0x13),

    GROUP_CREATE(0x20),
    GROUP_JOIN(0x21),
    GROUP_LEAVE(0x22),
    GROUP_MSG(0x23),

    QR_GENERATE(0x30),
    QR_VALIDATE(0x31),
    HISTORY_SYNC(0x32),

    FILE_CHUNK(0x40),
    FILE_COMPLETE(0x41),

    KEY_EXCHANGE(0x50),

    SALES_QUERY(0x60),
    SALES_RESPONSE(0x61),

    PING(0xF0),
    DISCONNECT(0xFF);

    fun toByte(): Byte = code.toByte()

    companion object {
        private val lookup = HashMap<Int, OpCode>().apply {
            for (op in values()) put(op.code and 0xFF, op)
        }

        fun fromByte(b: Int): OpCode =
            lookup[b and 0xFF]
                ?: throw IllegalArgumentException("Opcode desconocido: 0x%02X".format(b and 0xFF))
    }
}