package dogmsg.client.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;


public final class Packet {

    public static final int MAGIC = 0xD06D;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 29;
    public static final int CHECKSUM_SIZE = 4;

    // Flags (mascaras de bit del campo Flags)
    public static final int FLAG_ENCRYPTED = 0x01;  // bit 0
    public static final int FLAG_COMPRESSED = 0x02; // bit 1
    public static final int FLAG_IS_GROUP = 0x04;   // bit 2

    private final OpCode opcode;
    private final long sequence;     // uint32
    private final long senderId;     // uint32
    private final long receiverId;   // uint32
    private final long timestamp;    // int64 (millis)
    private final int flags;         // uint8
    private final byte[] payload;

    public Packet(OpCode opcode, long sequence, long senderId, long receiverId,
                  long timestamp, int flags, byte[] payload) {
        this.opcode = opcode;
        this.sequence = sequence & 0xFFFFFFFFL;
        this.senderId = senderId & 0xFFFFFFFFL;
        this.receiverId = receiverId & 0xFFFFFFFFL;
        this.timestamp = timestamp;
        this.flags = flags & 0xFF;
        this.payload = (payload == null) ? new byte[0] : payload;
    }

    // ----- Getters -----
    public OpCode opcode()     { return opcode; }
    public long sequence()     { return sequence; }
    public long senderId()     { return senderId; }
    public long receiverId()   { return receiverId; }
    public long timestamp()    { return timestamp; }
    public int flags()         { return flags; }
    public byte[] payload()    { return payload; }

    public boolean isEncrypted()  { return (flags & FLAG_ENCRYPTED) != 0; }
    public boolean isCompressed() { return (flags & FLAG_COMPRESSED) != 0; }
    public boolean isGroup()      { return (flags & FLAG_IS_GROUP) != 0; }

    /** Payload interpretado como texto UTF-8 (util para JSON / MSG_TEXT). */
    public String payloadAsString() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Serializa la trama completa a bytes lista para escribir en el socket.
     */
    public byte[] toBytes() {
        int total = HEADER_SIZE + payload.length + CHECKSUM_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) MAGIC);          // 2  Magic
        buf.put((byte) VERSION);              // 1  Version
        buf.put(opcode.toByte());             // 1  Opcode
        buf.putInt((int) sequence);           // 4  Sequence
        buf.putInt((int) senderId);           // 4  Sender ID
        buf.putInt((int) receiverId);         // 4  Receiver ID
        buf.putLong(timestamp);               // 8  Timestamp
        buf.put((byte) flags);                // 1  Flags
        buf.putInt(payload.length);           // 4  Payload Length
        buf.put(payload);                     //    Payload

        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, HEADER_SIZE + payload.length);
        buf.putInt((int) crc.getValue());     // 4  Checksum

        return buf.array();
    }

    /**
     * Calcula el CRC32 que corresponde a un buffer header+payload ya formado.
     * Lo usa {@link PacketParser} para validar tramas entrantes.
     */
    public static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    // ---- Constructores de conveniencia ----

    /** Crea un paquete con timestamp actual. */
    public static Packet now(OpCode op, long seq, long from, long to, int flags, byte[] payload) {
        return new Packet(op, seq, from, to, System.currentTimeMillis(), flags, payload);
    }

    @Override
    public String toString() {
        return String.format(
                "Packet{op=%s seq=%d from=%d to=%d flags=0x%02X len=%d}",
                opcode, sequence, senderId, receiverId, flags, payload.length);
    }
}