package dogmsg;

import dogmsg.protocol.OpCode;
import dogmsg.protocol.Packet;

import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MessageRouter.java
 * Dog Messenger — Enrutamiento de mensajes entre clientes.
 *
 * Lógica (sección 7.1 — T-A4 del plan):
 *
 *  MSG_TEXT / MSG_IMAGE:
 *    - Si destinatario online → enviar directo + generar MSG_ACK al remitente.
 *    - Si destinatario offline → encolar en OfflineQueue + ACK al remitente.
 *
 *  GROUP_MSG:
 *    - Delegar a GroupManager.broadcastToGroup().
 *    - ACK al remitente.
 *
 *  Persistencia:
 *    - Todos los mensajes se persisten en BD (tabla messages) independientemente
 *      del estado del destinatario. El servidor almacena los bytes cifrados tal cual.
 *
 * Rate limiting (sección 10.3):
 *    - Se rechaza si un cliente envía más de 100 paquetes/segundo.
 *    - Implementado en ClientHandler.
 */
public class MessageRouter {

    private static final Logger log = Logger.getLogger(MessageRouter.class.getName());

    private final DatabaseManager           db;
    private final Map<Integer, ClientHandler> onlineMap;
    private final OfflineQueue              offline;
    private final GroupManager              groupManager;

    public MessageRouter(DatabaseManager db,
                         Map<Integer, ClientHandler> onlineMap,
                         OfflineQueue offline,
                         GroupManager groupManager) {
        this.db           = db;
        this.onlineMap    = onlineMap;
        this.offline      = offline;
        this.groupManager = groupManager;
    }

    // ════════════════════════════════════════════════════════════════════════
    // MSG_TEXT / MSG_IMAGE (privados)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Enruta un mensaje privado (texto o imagen) de un cliente a otro.
     *
     * @param packet paquete MSG_TEXT o MSG_IMAGE recibido del remitente
     */
    public void routePrivate(Packet packet) {
        int     senderId   = packet.senderId;
        int     receiverId = packet.receiverId;
        OpCode  type       = packet.opcode;

        // Persistir en BD (bytes cifrados, el servidor no los lee)
        try {
            String msgType = (type == OpCode.MSG_IMAGE) ? "image" : "text";
            db.saveMessage(senderId, receiverId, false, msgType, packet.payload);
        } catch (SQLException e) {
            log.warning("[Router] Error persistiendo mensaje: " + e.getMessage());
        }

        // Enrutar
        ClientHandler dest = onlineMap.get(receiverId);
        if (dest != null) {
            dest.sendPacket(packet);
            log.info("[Router] " + type + " " + senderId + "→" + receiverId + " (online)");
        } else {
            offline.enqueueForUser(receiverId, packet);
            log.info("[Router] " + type + " " + senderId + "→" + receiverId + " (offline, encolado)");
        }

        // ACK al remitente
        sendAck(senderId, packet.sequence, receiverId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // GROUP_MSG
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Enruta un mensaje de grupo. El receiverId del paquete es el groupId.
     */
    public void routeGroup(Packet packet) {
        int groupId  = packet.receiverId;
        int senderId = packet.senderId;

        // Persistir mensaje de grupo
        try {
            db.saveMessage(senderId, groupId, true, "text", packet.payload);
        } catch (SQLException e) {
            log.warning("[Router] Error persistiendo GROUP_MSG: " + e.getMessage());
        }

        // Broadcast a todos los miembros
        groupManager.broadcastToGroup(groupId, packet, senderId, onlineMap, offline);

        // ACK al remitente
        sendAck(senderId, packet.sequence, groupId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ACK interno
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Genera y envía un MSG_ACK al remitente confirmando la entrega del mensaje.
     *
     * Payload del ACK: {"seq":N,"delivered_to":M}
     */
    private void sendAck(int senderId, int originalSeq, int deliveredTo) {
        ClientHandler sender = onlineMap.get(senderId);
        if (sender == null) return;

        String ackPayload = String.format(
                "{\"seq\":%d,\"delivered_to\":%d}", originalSeq, deliveredTo);
        Packet ack      = new Packet(OpCode.MSG_ACK,
                0, senderId,    // from=SERVER(0), to=remitente
                ackPayload);
        ack.sequence    = originalSeq;
        sender.sendPacket(ack);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Historial (para HISTORY_SYNC en clonación QR)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Recupera el historial de mensajes de un usuario desde BD y lo empaqueta
     * en un único paquete HISTORY_SYNC.
     *
     * El payload es un JSON array de mensajes serializado.
     * Se usa para la clonación QR (T-A8).
     *
     * @param userId   usuario cuyo historial se solicita
     * @param targetId cliente al que se enviará el historial
     * @return paquete HISTORY_SYNC listo para enviar
     */
    public Packet buildHistorySync(int userId, int targetId) {
        StringBuilder sb = new StringBuilder("[");
        try {
            // Historial de conversaciones privadas (últimos 500 mensajes)
            java.sql.ResultSet rs = db.getMessageHistory(userId, 0, 500);
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                sb.append(String.format(
                    "{\"id\":%d,\"from\":%d,\"to\":%d,\"type\":\"%s\",\"ts\":\"%s\"}",
                    rs.getLong("id"),
                    rs.getInt("sender_id"),
                    rs.getInt("receiver_id"),
                    rs.getString("msg_type"),
                    rs.getString("timestamp")));
                first = false;
            }
            rs.close();
        } catch (Exception e) {
            log.warning("[Router] Error construyendo historial: " + e.getMessage());
        }
        sb.append("]");

        Packet hist      = new Packet(OpCode.HISTORY_SYNC, 0, targetId, sb.toString());
        hist.receiverId  = targetId;
        return hist;
    }
}
