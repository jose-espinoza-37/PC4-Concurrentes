package dogmsg.client;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * T-B8: Cache local de mensajes en SQLite.
 *
 * <p>Permite abrir una conversacion mostrando los mensajes recientes sin
 * consultar al servidor, y sincronizar cuando hay diferencias. Usa el driver
 * {@code sqlite-jdbc} (declarado en build.gradle). El acceso esta sincronizado
 * porque el hilo de UI y el de red pueden escribir a la vez.</p>
 */
public class LocalCache implements AutoCloseable {

    /** Representa una fila de mensaje cacheado. */
    public static class CachedMessage {
        public long id;
        public long peerId;       // usuario o grupo de la conversacion
        public boolean isGroup;
        public long senderId;
        public String type;       // text | image | file
        public String text;       // texto en claro (ya descifrado localmente)
        public long timestamp;
        public String status;     // sent | delivered | read
    }

    private final Connection conn;
    private final Object lock = new Object();

    public LocalCache(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
    }

    private void initSchema() throws SQLException {
        synchronized (lock) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cache_messages (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  server_id INTEGER," +              // id en el servidor (si se conoce)
                    "  peer_id INTEGER NOT NULL," +       // contraparte de la conversacion
                    "  is_group INTEGER DEFAULT 0," +
                    "  sender_id INTEGER NOT NULL," +
                    "  type TEXT DEFAULT 'text'," +
                    "  text TEXT," +                      // texto en claro (descifrado)
                    "  timestamp INTEGER NOT NULL," +
                    "  status TEXT DEFAULT 'sent'" +
                    ")");
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_peer_time " +
                    "ON cache_messages(peer_id, is_group, timestamp)");
            }
        }
    }

    /** Inserta un mensaje en el cache local y devuelve su id local. */
    public long insert(CachedMessage m) throws SQLException {
        synchronized (lock) {
            String sql = "INSERT INTO cache_messages " +
                    "(server_id, peer_id, is_group, sender_id, type, text, timestamp, status) " +
                    "VALUES (?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, m.id);
                ps.setLong(2, m.peerId);
                ps.setInt(3, m.isGroup ? 1 : 0);
                ps.setLong(4, m.senderId);
                ps.setString(5, m.type);
                ps.setString(6, m.text);
                ps.setLong(7, m.timestamp);
                ps.setString(8, m.status);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1;
                }
            }
        }
    }

    /** Carga los ultimos {@code limit} mensajes de una conversacion (cronologico). */
    public List<CachedMessage> recent(long peerId, boolean isGroup, int limit) throws SQLException {
        synchronized (lock) {
            String sql = "SELECT id, peer_id, is_group, sender_id, type, text, timestamp, status " +
                    "FROM cache_messages WHERE peer_id=? AND is_group=? " +
                    "ORDER BY timestamp DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, peerId);
                ps.setInt(2, isGroup ? 1 : 0);
                ps.setInt(3, limit);
                List<CachedMessage> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        CachedMessage m = new CachedMessage();
                        m.id = rs.getLong("id");
                        m.peerId = rs.getLong("peer_id");
                        m.isGroup = rs.getInt("is_group") == 1;
                        m.senderId = rs.getLong("sender_id");
                        m.type = rs.getString("type");
                        m.text = rs.getString("text");
                        m.timestamp = rs.getLong("timestamp");
                        m.status = rs.getString("status");
                        out.add(m);
                    }
                }
                java.util.Collections.reverse(out); // a orden cronologico ascendente
                return out;
            }
        }
    }

    /** Actualiza el estado de un mensaje (p.ej. sent -> delivered). */
    public void updateStatus(long localId, String status) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE cache_messages SET status=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setLong(2, localId);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}