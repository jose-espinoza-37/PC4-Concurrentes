package dogmsg.client.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public final class PacketParser {

    /** Limite de seguridad para evitar OOM ante payloads corruptos/maliciosos. */
    public static final int MAX_PAYLOAD = 64 * 1024 * 1024; // 64 MB

    private final DataInputStream in;

    public PacketParser(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /**
     * Lee la siguiente trama del stream. Bloquea hasta tener una trama completa.
     *
     * @return el {@link Packet} leido, o {@code null} si el stream se cerro
     *         limpiamente antes de iniciar una nueva trama.
     * @throws ProtocolException si la trama esta corrupta.
     * @throws IOException        ante errores de E/S.
     */
    public Packet readPacket() throws IOException {
        byte[] header = new byte[Packet.HEADER_SIZE];

        // Intento de leer el primer byte; si EOF aqui, fin de conexion limpio.
        int first = in.read();
        if (first == -1) {
            return null;
        }
        header[0] = (byte) first;
        in.readFully(header, 1, Packet.HEADER_SIZE - 1);

        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);

        int magic = hb.getShort() & 0xFFFF;
        if (magic != Packet.MAGIC) {
            throw new ProtocolException(
                    String.format("Magic invalido: 0x%04X (esperado 0x%04X)", magic, Packet.MAGIC));
        }

        int version = hb.get() & 0xFF;
        int opByte = hb.get() & 0xFF;
        long sequence = hb.getInt() & 0xFFFFFFFFL;
        long senderId = hb.getInt() & 0xFFFFFFFFL;
        long receiverId = hb.getInt() & 0xFFFFFFFFL;
        long timestamp = hb.getLong();
        int flags = hb.get() & 0xFF;
        long payloadLenU = hb.getInt() & 0xFFFFFFFFL;

        if (payloadLenU > MAX_PAYLOAD) {
            throw new ProtocolException("Payload demasiado grande: " + payloadLenU);
        }
        int payloadLen = (int) payloadLenU;

        byte[] payload = new byte[payloadLen];
        in.readFully(payload);

        byte[] checksumBytes = new byte[Packet.CHECKSUM_SIZE];
        in.readFully(checksumBytes);
        long received = ByteBuffer.wrap(checksumBytes).order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;

        // Recalcular CRC32 sobre header + payload
        byte[] crcInput = new byte[Packet.HEADER_SIZE + payloadLen];
        System.arraycopy(header, 0, crcInput, 0, Packet.HEADER_SIZE);
        System.arraycopy(payload, 0, crcInput, Packet.HEADER_SIZE, payloadLen);
        long computed = Packet.crc32(crcInput, 0, crcInput.length);

        if (computed != received) {
            throw new ProtocolException(
                    String.format("Checksum invalido: recibido=0x%08X calculado=0x%08X", received, computed));
        }

        OpCode opcode = OpCode.fromByte(opByte);
        // version actualmente se ignora salvo logging; el contrato fija v1.
        return new Packet(opcode, sequence, senderId, receiverId, timestamp, flags, payload);
    }

    /** Excepcion de protocolo (trama corrupta / invalida). */
    public static class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }
}