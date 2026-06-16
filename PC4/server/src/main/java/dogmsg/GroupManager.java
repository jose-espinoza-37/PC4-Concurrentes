package dogmsg;

import dogmsg.protocol.OpCode;
import dogmsg.protocol.Packet;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * GroupManager.java
 * Dog Messenger — CRUD de grupos + enrutamiento de mensajes de grupo.
 *
 * Tareas (plan sección 7.1 — T-A6):
 *  - Crear grupos con nombre y admin.
 *  - Agregar/eliminar miembros (solo el admin puede eliminar).
 *  - Salir voluntariamente de un grupo.
 *  - Broadcast de GROUP_MSG a todos los miembros online, y encolar para offline.
 *
 * Mantiene un caché en memoria de groupId → Set<userId> para evitar
 * consultas a BD en cada mensaje de grupo.
 */
public class GroupManager {

    private static final Logger log = Logger.getLogger(GroupManager.class.getName());

    private final DatabaseManager db;

    /** Caché en memoria: groupId → conjunto de userIds miembros. */
    private final Map<Integer, Set<Integer>> membersCache = new ConcurrentHashMap<>();

    /** Caché: groupId → adminId. */
    private final Map<Integer, Integer> adminCache = new ConcurrentHashMap<>();

    public GroupManager(DatabaseManager db) {
        this.db = db;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Creación y gestión
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Crea un grupo nuevo.
     *
     * @param name    nombre del grupo
     * @param adminId userId del creador (será admin)
     * @return groupId generado, o -1 si hubo error
     */
    public int createGroup(String name, int adminId) {
        try {
            int gid = db.createGroup(name, adminId);
            if (gid > 0) {
                Set<Integer> members = ConcurrentHashMap.newKeySet();
                members.add(adminId);
                membersCache.put(gid, members);
                adminCache.put(gid, adminId);
                log.info("[Groups] Grupo creado: id=" + gid + " name='" + name
                        + "' admin=" + adminId);
            }
            return gid;
        } catch (SQLException e) {
            log.severe("[Groups] Error creando grupo: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Agrega un miembro al grupo.
     * Solo el admin puede agregar usuarios (validación externa en ClientHandler).
     *
     * @return true si fue agregado correctamente
     */
    public boolean addMember(int groupId, int userId, int requesterId) {
        try {
            if (!isAdmin(groupId, requesterId)) return false;
            db.addGroupMember(groupId, userId);
            getOrLoadMembers(groupId).add(userId);
            log.info("[Groups] Miembro agregado: userId=" + userId + " groupId=" + groupId);
            return true;
        } catch (SQLException e) {
            log.severe("[Groups] Error agregando miembro: " + e.getMessage());
            return false;
        }
    }

    /**
     * Elimina un miembro del grupo (solo el admin puede eliminar a otros).
     * Un miembro puede eliminarse a sí mismo (salir).
     */
    public boolean removeMember(int groupId, int targetUserId, int requesterId) {
        try {
            // Solo el admin puede eliminar a otros; cualquiera puede salir
            if (targetUserId != requesterId && !isAdmin(groupId, requesterId)) return false;
            db.removeGroupMember(groupId, targetUserId);
            Set<Integer> members = membersCache.get(groupId);
            if (members != null) members.remove(targetUserId);
            log.info("[Groups] Miembro eliminado: userId=" + targetUserId
                    + " groupId=" + groupId);
            return true;
        } catch (SQLException e) {
            log.severe("[Groups] Error eliminando miembro: " + e.getMessage());
            return false;
        }
    }

    /** ¿Es el usuario admin del grupo? */
    public boolean isAdmin(int groupId, int userId) {
        Integer admin = adminCache.get(groupId);
        if (admin != null) return admin == userId;
        try {
            return db.isGroupAdmin(groupId, userId);
        } catch (SQLException e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Miembros
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Devuelve el conjunto de userIds miembros de un grupo.
     * Carga desde BD si no está en caché.
     */
    public Set<Integer> getMembers(int groupId) {
        try {
            return getOrLoadMembers(groupId);
        } catch (SQLException e) {
            log.warning("[Groups] Error cargando miembros groupId=" + groupId);
            return Collections.emptySet();
        }
    }

    private Set<Integer> getOrLoadMembers(int groupId) throws SQLException {
        return membersCache.computeIfAbsent(groupId, gid -> {
            Set<Integer> set = ConcurrentHashMap.newKeySet();
            try {
                ResultSet rs = db.getGroupMembers(gid);
                while (rs.next()) set.add(rs.getInt("user_id"));
                rs.close();
            } catch (SQLException e) {
                log.warning("[Groups] Error cargando miembros: " + e.getMessage());
            }
            return set;
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // Broadcast de mensajes de grupo
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Distribuye un paquete GROUP_MSG a todos los miembros del grupo.
     * Si un miembro está online → envío directo.
     * Si está offline → encola en OfflineQueue.
     *
     * @param groupId    ID del grupo destino
     * @param packet     paquete GROUP_MSG con el contenido
     * @param senderId   userId del remitente (excluido del broadcast)
     * @param onlineMap  mapa de userId → ClientHandler de conexiones activas
     * @param offline    referencia a la cola offline
     */
    public void broadcastToGroup(int groupId, Packet packet, int senderId,
                                 Map<Integer, ClientHandler> onlineMap,
                                 OfflineQueue offline) {
        Set<Integer> members = getMembers(groupId);
        if (members.isEmpty()) {
            log.warning("[Groups] Grupo vacío o inexistente: " + groupId);
            return;
        }

        int sent = 0, queued = 0;
        for (int memberId : members) {
            if (memberId == senderId) continue; // no reenviar al emisor

            ClientHandler handler = onlineMap.get(memberId);
            if (handler != null) {
                // Crear copia del paquete con receiverId correcto
                Packet copy = buildCopy(packet, memberId);
                handler.sendPacket(copy);
                sent++;
            } else {
                // Encolar para entrega diferida
                Packet copy = buildCopy(packet, memberId);
                offline.enqueueForUser(memberId, copy);
                queued++;
            }
        }
        log.info("[Groups] GROUP_MSG groupId=" + groupId
                + " | online=" + sent + " offline=" + queued);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Número total de grupos activos en caché. */
    public int groupCount() { return membersCache.size(); }

    /** Clona un paquete cambiando solo el receiverId. */
    private Packet buildCopy(Packet src, int newReceiverId) {
        Packet copy      = new Packet();
        copy.opcode      = src.opcode;
        copy.sequence    = src.sequence;
        copy.senderId    = src.senderId;
        copy.receiverId  = newReceiverId;
        copy.timestamp   = src.timestamp;
        copy.flags       = src.flags;
        copy.payload     = src.payload;
        copy.setGroup(true);
        return copy;
    }
}
