package dogmsg.protocol;

/**
 * OpCode.java
 * Dog Messenger — Enum de opcodes del protocolo binario.
 *
 * Protocolo binario (sección 3 y 4 del plan):
 *   Cada opcode corresponde a un byte en el header de la trama.
 *
 *   Dirección: C=Cliente, S=Servidor
 */
public enum OpCode {

    // ── Autenticación ────────────────────────────────────────────────────────
    AUTH_REQUEST   (0x01, "Login o registro"),           // C -> S
    AUTH_RESPONSE  (0x02, "Resultado auth + token"),     // S -> C

    // ── Mensajes ─────────────────────────────────────────────────────────────
    MSG_TEXT       (0x10, "Mensaje de texto"),            // C -> S -> C
    MSG_IMAGE      (0x11, "Imagen metadata + chunks"),    // C -> S -> C
    MSG_FILE       (0x12, "Archivo metadata + chunks"),   // C -> S -> C
    MSG_ACK        (0x13, "Confirmación de entrega"),     // S -> C

    // ── Grupos ───────────────────────────────────────────────────────────────
    GROUP_CREATE   (0x20, "Crear grupo"),                 // C -> S
    GROUP_JOIN     (0x21, "Agregar miembro"),             // C -> S
    GROUP_LEAVE    (0x22, "Salir/eliminar de grupo"),     // C -> S
    GROUP_MSG      (0x23, "Mensaje a grupo broadcast"),   // C -> S -> C*

    // ── Clonación QR ─────────────────────────────────────────────────────────
    QR_GENERATE    (0x30, "Solicitar token de clonación"), // C -> S
    QR_VALIDATE    (0x31, "Validar token QR escaneado"),   // C -> S
    HISTORY_SYNC   (0x32, "Historial al dispositivo clonado"), // S -> C

    // ── Archivos ─────────────────────────────────────────────────────────────
    FILE_CHUNK     (0x40, "Chunk de archivo 64KB"),       // C <-> S
    FILE_COMPLETE  (0x41, "Archivo completado"),          // C <-> S

    // ── Encriptación ─────────────────────────────────────────────────────────
    KEY_EXCHANGE   (0x50, "Intercambio clave pública DH"), // C -> S -> C

    // ── Ventas ───────────────────────────────────────────────────────────────
    SALES_QUERY    (0x60, "Consulta al módulo de ventas"), // C -> S
    SALES_RESPONSE (0x61, "Respuesta del módulo de ventas"), // S -> C

    // ── Control ──────────────────────────────────────────────────────────────
    PING           (0xF0, "Keep-alive"),                  // C <-> S
    DISCONNECT     (0xFF, "Desconexión limpia");          // C -> S

    // ────────────────────────────────────────────────────────────────────────
    public final int    code;
    public final String description;

    OpCode(int code, String description) {
        this.code        = code;
        this.description = description;
    }

    /** Convierte un byte (0x00-0xFF) al OpCode correspondiente. */
    public static OpCode fromByte(int b) {
        for (OpCode op : values()) {
            if (op.code == (b & 0xFF)) return op;
        }
        throw new IllegalArgumentException(
                String.format("OpCode desconocido: 0x%02X", b & 0xFF));
    }

    /** Devuelve el byte de opcode como int sin signo. */
    public int toByte() { return code & 0xFF; }

    @Override
    public String toString() {
        return String.format("OpCode[0x%02X %s]", code, name());
    }
}
