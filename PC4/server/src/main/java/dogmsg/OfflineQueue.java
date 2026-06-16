package dogmsg;

import dogmsg.protocol.Packet;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * OfflineQueue.java
 * Dog Messenger — Cola persistente de mensajes para usuarios desconectados.
 *
 * Comportamiento (sección 7.1 — T-A5 del plan):
 *  1. Al intentar enrutar a un usuario offline → enqueueForUser().
 *  2. Al reconectar un usuario → flushToClient() entrega todos los mensajes en orden.
 *  3. Confirmada la entrega → los registros se borran de SQLite.
 *
 * Los paquetes se almacenan como bytes serializados (Packet.serialize()).
 * Al recuperarlos, se reconstruyen y se reenvían tal cual al cliente.
 */
public class OfflineQueue {

    private static final Logger log = Logger.getLogger(OfflineQueue.class.getName());

    private final DatabaseManager db;

    public OfflineQueue(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Encola un paquete serializado para entrega diferida.
     *
     * @param userId  ID del usuario destinatario (que está offline)
     * @param packet  Packet ya construido y listo para enviar
     */
    public void enqueueForUser(int userId, Packet packet) {
        try {
            byte[] data = packet.serialize();
            db.enqueueOffline(userId, data);
            log.info("[OfflineQueue] Encolado para userId=" + userId
                    + " op=" + packet.opcode);
        } catch (SQLException e) {
            log.severe("[OfflineQueue] Error encolando para userId=" + userId
                    + ": " + e.getMessage());
        }
    }

    /**
     * Recupera y entrega todos los mensajes encolados de un usuario.
     * Los envía al ClientHandler en orden cronológico y los elimina de la BD.
     *
     * @param userId  ID del usuario que acaba de reconectarse
     * @param handler ClientHandler activo del usuario
     * @return número de mensajes entregados
     */
    public int flushToClient(int userId, ClientHandler handler) {
        int delivered = 0;
        try {
            ResultSet rs = db.getOfflineQueue(userId);
            List<long[]> delivered_ids = new ArrayList<>();

            while (rs.next()) {
                long   queueId    = rs.getLong("id");
                byte[] packetData = rs.getBytes("packet_data");

                // Enviar los bytes raw directamente al cliente
                handler.sendRawBytes(packetData);
                delivered_ids.add(new long[]{queueId});
                delivered++;
            }
            rs.close();

            // Eliminar de la cola tras entregar
            for (long[] entry : delivered_ids) {
                db.dequeueOffline(entry[0]);
            }

            if (delivered > 0) {
                log.info("[OfflineQueue] Entregados " + delivered
                        + " mensajes encolados a userId=" + userId);
            }
        } catch (Exception e) {
            log.severe("[OfflineQueue] Error al hacer flush para userId=" + userId
                    + ": " + e.getMessage());
        }
        return delivered;
    }

    /**
     * Devuelve cuántos mensajes hay encolados para un usuario.
     * Útil para notificar al cliente al reconectarse.
     */
    public int pendingCount(int userId) {
        try {
            ResultSet rs = db.getOfflineQueue(userId);
            int count = 0;
            while (rs.next()) count++;
            rs.close();
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}
