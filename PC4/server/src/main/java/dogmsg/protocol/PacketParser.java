package dogmsg.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.logging.Logger;

/**
 * PacketParser.java
 * Dog Messenger — Deserialización de tramas binarias desde un InputStream.
 *
 * Protocolo de lectura (sección 7.1 — T-A1 del plan):
 *   1. Leer exactamente 29 bytes de header.
 *   2. Validar magic number (0xD06D) y checksum parcial.
 *   3. Leer payloadLen bytes de payload.
 *   4. Leer 4 bytes de checksum y verificar CRC32 completo.
 *   5. Construir y devolver el objeto Packet.
 *
 * Esta clase es stateless: se puede usar desde múltiples hilos,
 * siempre que cada hilo tenga su propio InputStream.
 */
public class PacketParser {

    private static final Logger log = Logger.getLogger(PacketParser.class.getName());

    /**
     * Lee y parsea el siguiente paquete del InputStream.
     *
     * Bloquea hasta recibir los bytes completos o hasta que el stream se cierre.
     *
     * @param in InputStream del socket (BufferedInputStream recomendado)
     * @return Packet completamente deserializado
     * @throws IOException si el stream se cierra, hay corrupción o magic inválido
     */
    public static Packet read(InputStream in) throws IOException {
        // ── 1. Leer header fijo (29 bytes) ──────────────────────────────────
        byte[] header = readExactly(in, Packet.HEADER_SIZE);

        ByteBuffer hBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);

        // ── 2. Validar magic ─────────────────────────────────────────────────
        int magic = hBuf.getShort() & 0xFFFF;
        if (magic != Packet.MAGIC) {
            throw new IOException(
                    String.format("Magic inválido: 0x%04X (esperado 0x%04X)", magic, Packet.MAGIC));
        }

        byte  version    = hBuf.get();
        int   opcodeRaw  = hBuf.get() & 0xFF;
        int   sequence   = hBuf.getInt();
        int   senderId   = hBuf.getInt();
        int   receiverId = hBuf.getInt();
        long  timestamp  = hBuf.getLong();
        byte  flags      = hBuf.get();
        int   payloadLen = hBuf.getInt();

        // Validar opcode
        OpCode opcode;
        try {
            opcode = OpCode.fromByte(opcodeRaw);
        } catch (IllegalArgumentException e) {
            throw new IOException("OpCode desconocido: 0x" + Integer.toHexString(opcodeRaw), e);
        }

        // Validar tamaño del payload (limite de seguridad: 25 MB)
        if (payloadLen < 0 || payloadLen > 25 * 1024 * 1024) {
            throw new IOException("Payload length inválido: " + payloadLen);
        }

        // ── 3. Leer payload ──────────────────────────────────────────────────
        byte[] payload = (payloadLen > 0) ? readExactly(in, payloadLen) : new byte[0];

        // ── 4. Leer y verificar checksum ────────────────────────────────────
        byte[] crcBytes = readExactly(in, 4);
        ByteBuffer crcBuf = ByteBuffer.wrap(crcBytes).order(ByteOrder.BIG_ENDIAN);
        long receivedCrc = crcBuf.getInt() & 0xFFFFFFFFL;

        // Calcular CRC32 sobre header + payload
        CRC32 crc32 = new CRC32();
        crc32.update(header);
        crc32.update(payload);
        long expectedCrc = crc32.getValue();

        if (receivedCrc != expectedCrc) {
            throw new IOException(
                    String.format("Checksum CRC32 inválido: recibido=0x%08X esperado=0x%08X",
                            receivedCrc, expectedCrc));
        }

        // ── 5. Construir Packet ──────────────────────────────────────────────
        Packet pkt      = new Packet();
        pkt.opcode      = opcode;
        pkt.sequence    = sequence;
        pkt.senderId    = senderId;
        pkt.receiverId  = receiverId;
        pkt.timestamp   = timestamp;
        pkt.flags       = flags;
        pkt.payload     = payload;

        log.fine(() -> "Packet recibido: " + pkt);
        return pkt;
    }

    /**
     * Lee exactamente {@code n} bytes del stream.
     * Lanza EOFException si el stream se cierra antes de completar.
     */
    public static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf     = new byte[n];
        int    offset  = 0;
        while (offset < n) {
            int read = in.read(buf, offset, n - offset);
            if (read == -1) {
                throw new EOFException(
                        "Stream cerrado tras leer " + offset + " de " + n + " bytes");
            }
            offset += read;
        }
        return buf;
    }
}
