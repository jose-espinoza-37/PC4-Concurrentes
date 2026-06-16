package dogmsg;

import dogmsg.protocol.OpCode;
import dogmsg.protocol.Packet;

import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * EncryptionBroker.java
 * Dog Messenger — Broker de intercambio de claves Diffie-Hellman para E2E.
 *
 * Comportamiento (sección 10.1 y T-A9 del plan):
 *  1. El cliente A genera su par DH y envía KEY_EXCHANGE con su clave pública.
 *  2. El broker almacena la clave pública de A en BD (tabla public_keys).
 *  3. Si el cliente B ya tiene su clave registrada, se la reenvía a A (y viceversa).
 *  4. El servidor NUNCA almacena claves privadas ni puede derivar la clave compartida.
 *  5. Todos los MSG_TEXT/IMAGE/FILE con flag encrypted=1 se enrutan sin descifrar.
 *
 * Payload de KEY_EXCHANGE:
 *   Bytes de la clave pública DH en formato raw (SubjectPublicKeyInfo o simplemente
 *   los bytes de la clave pública que el cliente decida usar).
 *   En el campo receiverId del paquete se indica con quién se quiere intercambiar.
 */
public class EncryptionBroker {

    private static final Logger log = Logger.getLogger(EncryptionBroker.class.getName());

    private final DatabaseManager           db;

    public EncryptionBroker(DatabaseManager db) {
        this.db = db;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Manejo de KEY_EXCHANGE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Procesa un paquete KEY_EXCHANGE recibido de un cliente.
     *
     * Flujo:
     *  a) Almacena la clave pública del remitente en BD.
     *  b) Busca la clave pública del destinatario.
     *  c) Si existe → reenvía ambas claves a los respectivos clientes.
     *  d) Si no existe → espera (cuando B registre su clave, llamar de nuevo).
     *
     * @param packet     paquete KEY_EXCHANGE recibido
     * @param onlineMap  mapa de userId → ClientHandler activos
     */
    public void handleKeyExchange(Packet packet, Map<Integer, ClientHandler> onlineMap) {
        int    senderId    = packet.senderId;
        int    receiverId  = packet.receiverId;
        byte[] senderPubKey = packet.payload;

        if (senderPubKey == null || senderPubKey.length == 0) {
            log.warning("[E2E] KEY_EXCHANGE sin payload de userId=" + senderId);
            return;
        }

        try {
            // Almacenar clave pública del remitente
            db.storePublicKey(senderId, senderPubKey);
            log.info("[E2E] Clave pública almacenada para userId=" + senderId
                    + " (dest=" + receiverId + ")");

            // Buscar clave del destinatario
            byte[] receiverPubKey = db.getPublicKey(receiverId);

            // Si B ya tiene clave → intercambio completo
            if (receiverPubKey != null) {
                // Enviar clave de B a A
                sendKeyTo(senderId, receiverId, receiverPubKey, onlineMap);
                // Enviar clave de A a B
                sendKeyTo(receiverId, senderId, senderPubKey, onlineMap);
                log.info("[E2E] Intercambio completo entre userId=" + senderId
                        + " y userId=" + receiverId);
            } else {
                log.info("[E2E] userId=" + receiverId
                        + " aún no tiene clave pública. Esperando.");
                // Cuando B envíe su KEY_EXCHANGE con dest=A, este método se llamará
                // de nuevo y detectará que A ya tiene clave → completará el intercambio.
            }

        } catch (SQLException e) {
            log.severe("[E2E] Error en KEY_EXCHANGE: " + e.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Envía la clave pública de {@code fromId} al cliente {@code toId}.
     * Si {@code toId} no está online, no se puede entregar (el intercambio
     * deberá reintentarse al reconectar).
     */
    private void sendKeyTo(int toId, int fromId, byte[] pubKey,
                           Map<Integer, ClientHandler> onlineMap) {
        ClientHandler handler = onlineMap.get(toId);
        if (handler == null) {
            log.info("[E2E] userId=" + toId + " offline; no se puede entregar clave ahora.");
            return;
        }

        Packet resp      = new Packet();
        resp.opcode      = OpCode.KEY_EXCHANGE;
        resp.senderId    = fromId;   // "esta clave es DE fromId"
        resp.receiverId  = toId;
        resp.timestamp   = System.currentTimeMillis();
        resp.payload     = pubKey;
        handler.sendPacket(resp);

        log.fine("[E2E] Clave de userId=" + fromId + " enviada a userId=" + toId);
    }
}
