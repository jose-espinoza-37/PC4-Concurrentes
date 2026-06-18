package dogmsg.client.protocol;

/**
 * Opcodes del protocolo Dog Messenger (Seccion 3 de la guia).
 *
 * <p>El valor numerico de cada opcode es FIJO y forma parte del contrato
 * compartido entre Java (servidor + desktop), Kotlin (Android) y Python
 * (ventas). No cambiar.</p>
 */
public enum OpCode {
    AUTH_REQUEST(0x01),   // C -> S : login o registro
    AUTH_RESPONSE(0x02),  // S -> C : resultado + token

    MSG_TEXT(0x10),       // C -> S -> C : mensaje de texto
    MSG_IMAGE(0x11),      // C -> S -> C : imagen (metadata + chunks)
    MSG_FILE(0x12),       // C -> S -> C : archivo (metadata + chunks)
    MSG_ACK(0x13),        // S -> C : confirmacion de entrega

    GROUP_CREATE(0x20),   // C -> S : crear grupo
    GROUP_JOIN(0x21),     // C -> S : agregar miembro
    GROUP_LEAVE(0x22),    // C -> S : salir / eliminar de grupo
    GROUP_MSG(0x23),      // C -> S -> C* : mensaje a grupo (broadcast)

    QR_GENERATE(0x30),    // C -> S : solicitar token de clonacion
    QR_VALIDATE(0x31),    // C -> S : validar token QR escaneado
    HISTORY_SYNC(0x32),   // S -> C : envio de historial al dispositivo clonado

    FILE_CHUNK(0x40),     // C <-> S : chunk de archivo (64KB)
    FILE_COMPLETE(0x41),  // C <-> S : archivo completado

    KEY_EXCHANGE(0x50),   // C -> S -> C : intercambio clave publica DH

    SALES_QUERY(0x60),    // C -> S : consulta al modulo de ventas
    SALES_RESPONSE(0x61), // S -> C : respuesta del modulo de ventas

    PING(0xF0),           // C <-> S : keep-alive
    DISCONNECT(0xFF);     // C -> S : desconexion limpia

    private final int code;

    OpCode(int code) {
        this.code = code;
    }

    /** Devuelve el byte (0-255) que viaja en la trama. */
    public int code() {
        return code;
    }

    /** Mismo valor pero como {@code byte} con signo, util para escribir. */
    public byte toByte() {
        return (byte) code;
    }

    private static final OpCode[] LOOKUP = new OpCode[256];
    static {
        for (OpCode op : values()) {
            LOOKUP[op.code & 0xFF] = op;
        }
    }

    /**
     * Convierte un byte leido de la red en su OpCode.
     *
     * @throws IllegalArgumentException si el opcode es desconocido.
     */
    public static OpCode fromByte(int b) {
        OpCode op = LOOKUP[b & 0xFF];
        if (op == null) {
            throw new IllegalArgumentException(
                    String.format("Opcode desconocido: 0x%02X", b & 0xFF));
        }
        return op;
    }
}