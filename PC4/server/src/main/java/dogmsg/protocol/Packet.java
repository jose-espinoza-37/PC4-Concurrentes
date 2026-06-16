package dogmsg.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Packet.java
 * Dog Messenger — Serialización/deserialización de tramas binarias.
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │                    FORMATO DE TRAMA (29 bytes fijos)                │
 * ├──────────────┬────────┬───────────┬─────────────────────────────────┤
 * │ Campo        │ Bytes  │ Tipo      │ Descripción                     │
 * ├──────────────┼────────┼───────────┼─────────────────────────────────┤
 * │ Magic        │   2    │ uint16 BE │ 0xD06D — identificador          │
 * │ Version      │   1    │ uint8     │ 1                               │
 * │ Opcode       │   1    │ uint8     │ Tipo de mensaje                 │
 * │ Sequence     │   4    │ uint32 BE │ Número de secuencia             │
 * │ Sender ID    │   4    │ uint32 BE │ ID del remitente                │
 * │ Receiver ID  │   4    │ uint32 BE │ ID del destinatario             │
 * │ Timestamp    │   8    │ int64  BE │ Unix ms                         │
 * │ Flags        │   1    │ uint8     │ encrypted|compressed|is_group   │
 * │ Payload Len  │   4    │ uint32 BE │ Tamaño del payload              │
 * │ Payload      │  var   │ bytes     │ Datos del mensaje               │
 * │ Checksum     │   4    │ uint32 BE │ CRC32 del paquete completo      │
 * └──────────────┴────────┴───────────┴─────────────────────────────────┘
 * Header fijo: 29 bytes. Todo en Big-Endian.
 */
public class Packet {

    // ── Constantes del protocolo ──────────────────────────────────────────────
    public static final int    MAGIC          = 0xD06D;
    public static final byte   VERSION        = 1;
    public static final int    HEADER_SIZE    = 29;   // bytes fijos antes del payload
    public static final int    MAX_PAYLOAD    = 64 * 1024; // 64 KB por chunk

    // ── Offsets dentro del header ────────────────────────────────────────────
    private static final int OFF_MAGIC      = 0;   // 2 bytes
    private static final int OFF_VERSION    = 2;   // 1 byte
    private static final int OFF_OPCODE     = 3;   // 1 byte
    private static final int OFF_SEQUENCE   = 4;   // 4 bytes
    private static final int OFF_SENDER     = 8;   // 4 bytes
    private static final int OFF_RECEIVER   = 12;  // 4 bytes
    private static final int OFF_TIMESTAMP  = 16;  // 8 bytes
    private static final int OFF_FLAGS      = 24;  // 1 byte
    private static final int OFF_PAYLEN     = 25;  // 4 bytes
    // payload empieza en HEADER_SIZE (29)
    // checksum al final (4 bytes después del payload)

    // ── Bits del campo Flags ──────────────────────────────────────────────────
    public static final byte FLAG_ENCRYPTED  = 0x01;
    public static final byte FLAG_COMPRESSED = 0x02;
    public static final byte FLAG_IS_GROUP   = 0x04;

    // ── Campos del paquete ────────────────────────────────────────────────────
    public OpCode opcode;
    public int    sequence;     // uint32 (guardado como int, comparar con & 0xFFFFFFFFL)
    public int    senderId;     // uint32
    public int    receiverId;   // uint32
    public long   timestamp;    // int64 ms
    public byte   flags;
    public byte[] payload;      // puede ser null → longitud 0

    // ── Constructores ─────────────────────────────────────────────────────────

    public Packet() {}

    public Packet(OpCode opcode, int senderId, int receiverId, byte[] payload) {
        this.opcode     = opcode;
        this.senderId   = senderId;
        this.receiverId = receiverId;
        this.payload    = (payload != null) ? payload : new byte[0];
        this.timestamp  = System.currentTimeMillis();
        this.sequence   = 0;
        this.flags      = 0;
    }

    /** Constructor de texto (UTF-8 payload). */
    public Packet(OpCode opcode, int senderId, int receiverId, String textPayload) {
        this(opcode, senderId, receiverId,
             textPayload != null
                 ? textPayload.getBytes(StandardCharsets.UTF_8)
                 : new byte[0]);
    }

    // ── Flags helpers ─────────────────────────────────────────────────────────

    public boolean isEncrypted()  { return (flags & FLAG_ENCRYPTED)  != 0; }
    public boolean isCompressed() { return (flags & FLAG_COMPRESSED) != 0; }
    public boolean isGroup()      { return (flags & FLAG_IS_GROUP)   != 0; }

    public void setEncrypted(boolean v)  { flags = setBit(flags, FLAG_ENCRYPTED,  v); }
    public void setGroup(boolean v)      { flags = setBit(flags, FLAG_IS_GROUP,   v); }
    public void setCompressed(boolean v) { flags = setBit(flags, FLAG_COMPRESSED, v); }

    private static byte setBit(byte b, byte mask, boolean on) {
        return on ? (byte)(b | mask) : (byte)(b & ~mask);
    }

    // ── Payload como texto ────────────────────────────────────────────────────

    /** Devuelve el payload como String UTF-8 (nunca null). */
    public String getPayloadAsString() {
        if (payload == null || payload.length == 0) return "";
        return new String(payload, StandardCharsets.UTF_8);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SERIALIZACIÓN → bytes crudos listos para enviar al socket
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Serializa el paquete completo (header + payload + checksum) en un array de bytes.
     * Usa Big-Endian en todos los campos multi-byte, igual que lo define la sección 4.
     */
    public byte[] serialize() {
        byte[] pay  = (payload != null) ? payload : new byte[0];
        int    payLen = pay.length;

        // Buffer: HEADER_SIZE + payload + 4 bytes checksum
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payLen + 4);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short)(MAGIC & 0xFFFF));        // Magic      2
        buf.put(VERSION);                              // Version    1
        buf.put((byte)(opcode.toByte() & 0xFF));       // Opcode     1
        buf.putInt(sequence);                          // Sequence   4
        buf.putInt(senderId);                          // Sender ID  4
        buf.putInt(receiverId);                        // Receiver   4
        buf.putLong(timestamp);                        // Timestamp  8
        buf.put(flags);                                // Flags      1
        buf.putInt(payLen);                            // Pay Len    4
        // Total header: 2+1+1+4+4+4+8+1+4 = 29 ✓

        buf.put(pay);                                  // Payload   var

        // CRC32 de todos los bytes anteriores
        byte[] withoutCrc = new byte[HEADER_SIZE + payLen];
        buf.rewind();
        buf.get(withoutCrc);
        CRC32 crc32 = new CRC32();
        crc32.update(withoutCrc);
        buf.putInt((int)(crc32.getValue() & 0xFFFFFFFFL)); // Checksum 4

        return buf.array();
    }

    /**
     * Escribe el paquete serializado directamente al OutputStream.
     * Sincronizar externamente si varios hilos comparten el stream.
     */
    public void writeTo(OutputStream out) throws IOException {
        byte[] data = serialize();
        out.write(data);
        out.flush();
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return String.format("Packet{op=%s seq=%d from=%d to=%d flags=0x%02X payLen=%d}",
                opcode, sequence, senderId, receiverId, flags,
                payload != null ? payload.length : 0);
    }
}
