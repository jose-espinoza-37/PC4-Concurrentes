package com.dogmsg.android.protocol

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deserializa tramas [Packet] desde un [InputStream] TCP.
 *
 * Es el espejo EXACTO de PacketParser.java: lee el header fijo de 29 bytes,
 * determina la longitud del payload, lee payload + 4 bytes de checksum, y valida
 * magic + CRC32. Como usa el mismo ByteBuffer/CRC32 de la JVM, interpreta
 * byte a byte lo que produce el servidor Java (y debe coincidir con Python).
 */
class PacketParser(input: InputStream) {

    private val ins = DataInputStream(input)

    /**
     * Lee la siguiente trama. Bloquea hasta tener una trama completa.
     * Devuelve null si el stream se cerro limpiamente antes de iniciar otra trama.
     */
    @Throws(IOException::class)
    fun readPacket(): Packet? {
        val header = ByteArray(Packet.HEADER_SIZE)

        // Primer byte: si EOF aqui, cierre limpio de conexion.
        val first = ins.read()
        if (first == -1) return null
        header[0] = first.toByte()
        ins.readFully(header, 1, Packet.HEADER_SIZE - 1)

        val hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)

        val magic = hb.short.toInt() and 0xFFFF
        if (magic != Packet.MAGIC) {
            throw ProtocolException(
                "Magic invalido: 0x%04X (esperado 0x%04X)".format(magic, Packet.MAGIC)
            )
        }

        @Suppress("UNUSED_VARIABLE")
        val version = hb.get().toInt() and 0xFF
        val opByte = hb.get().toInt() and 0xFF
        val sequence = hb.int.toLong() and 0xFFFFFFFFL
        val senderId = hb.int.toLong() and 0xFFFFFFFFL
        val receiverId = hb.int.toLong() and 0xFFFFFFFFL
        val timestamp = hb.long
        val flags = hb.get().toInt() and 0xFF
        val payloadLenU = hb.int.toLong() and 0xFFFFFFFFL

        if (payloadLenU > MAX_PAYLOAD) {
            throw ProtocolException("Payload demasiado grande: $payloadLenU")
        }
        val payloadLen = payloadLenU.toInt()

        val payload = ByteArray(payloadLen)
        ins.readFully(payload)

        val checksumBytes = ByteArray(Packet.CHECKSUM_SIZE)
        ins.readFully(checksumBytes)
        val received = ByteBuffer.wrap(checksumBytes)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

        // Recalcular CRC32 sobre header + payload
        val crcInput = ByteArray(Packet.HEADER_SIZE + payloadLen)
        System.arraycopy(header, 0, crcInput, 0, Packet.HEADER_SIZE)
        System.arraycopy(payload, 0, crcInput, Packet.HEADER_SIZE, payloadLen)
        val computed = Packet.crc32(crcInput, 0, crcInput.size)

        if (computed != received) {
            throw ProtocolException(
                "Checksum invalido: recibido=0x%08X calculado=0x%08X".format(received, computed)
            )
        }

        val opcode = OpCode.fromByte(opByte)
        return Packet(opcode, sequence, senderId, receiverId, timestamp, flags, payload)
    }

    companion object {
        /** Limite de seguridad para evitar OOM ante payloads corruptos. */
        const val MAX_PAYLOAD = 64 * 1024 * 1024 // 64 MB
    }
}

/** Excepcion de protocolo (trama corrupta / invalida). */
class ProtocolException(message: String) : IOException(message)