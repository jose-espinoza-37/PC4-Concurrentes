package com.dogmsg.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Trama binaria del protocolo (Seccion 4). Produce EXACTAMENTE los mismos bytes
 * que la clase Packet de Java, porque usa el mismo orden de campos, big-endian
 * y CRC32 sobre header+payload (la JVM de Android comparte ByteBuffer y CRC32).
 *
 * Header fijo de 29 bytes + payload + 4 bytes de checksum.
 */
class Packet(
    val opcode: OpCode,
    sequence: Long,
    senderId: Long,
    receiverId: Long,
    val timestamp: Long,
    flags: Int,
    payload: ByteArray?
) {
    val sequence: Long = sequence and 0xFFFFFFFFL
    val senderId: Long = senderId and 0xFFFFFFFFL
    val receiverId: Long = receiverId and 0xFFFFFFFFL
    val flags: Int = flags and 0xFF
    val payload: ByteArray = payload ?: ByteArray(0)

    val isEncrypted: Boolean get() = (flags and FLAG_ENCRYPTED) != 0
    val isCompressed: Boolean get() = (flags and FLAG_COMPRESSED) != 0
    val isGroup: Boolean get() = (flags and FLAG_IS_GROUP) != 0

    fun payloadAsString(): String = String(payload, Charsets.UTF_8)

    fun toBytes(): ByteArray {
        val total = HEADER_SIZE + payload.size + CHECKSUM_SIZE
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)

        buf.putShort(MAGIC.toShort())     // 2  Magic 0xD06D
        buf.put(VERSION.toByte())         // 1  Version
        buf.put(opcode.toByte())          // 1  Opcode
        buf.putInt(sequence.toInt())      // 4  Sequence
        buf.putInt(senderId.toInt())      // 4  Sender ID
        buf.putInt(receiverId.toInt())    // 4  Receiver ID
        buf.putLong(timestamp)            // 8  Timestamp
        buf.put(flags.toByte())           // 1  Flags
        buf.putInt(payload.size)          // 4  Payload Length
        buf.put(payload)                  //    Payload

        val crc = CRC32()
        crc.update(buf.array(), 0, HEADER_SIZE + payload.size)
        buf.putInt(crc.value.toInt())     // 4  Checksum

        return buf.array()
    }

    override fun toString(): String =
        "Packet{op=$opcode seq=$sequence from=$senderId to=$receiverId flags=0x%02X len=${payload.size}}".format(flags)

    companion object {
        const val MAGIC = 0xD06D
        const val VERSION = 1
        const val HEADER_SIZE = 29
        const val CHECKSUM_SIZE = 4

        const val FLAG_ENCRYPTED = 0x01
        const val FLAG_COMPRESSED = 0x02
        const val FLAG_IS_GROUP = 0x04

        fun now(op: OpCode, seq: Long, from: Long, to: Long, flags: Int, payload: ByteArray?): Packet =
            Packet(op, seq, from, to, System.currentTimeMillis(), flags, payload)

        fun crc32(data: ByteArray, offset: Int, length: Int): Long {
            val crc = CRC32()
            crc.update(data, offset, length)
            return crc.value
        }
    }
}