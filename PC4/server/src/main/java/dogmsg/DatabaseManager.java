package dogmsg;

import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

/**
 * DatabaseManager.java
 * Dog Messenger — Interfaz con SQLite para persistencia total del servidor.
 *
 * Implementa el esquema completo de la sección 9 del plan:
 *   users, sessions, messages, groups, group_members,
 *   offline_queue, files, public_keys
 *
 * Thread-safety: Se usa WAL mode de SQLite + synchronized en cada método
 * para serializar los accesos concurrentes desde múltiples ClientHandler.
 */
public class DatabaseManager {

    private static final Logger log = Logger.getLogger(DatabaseManager.class.getName());

    private static final String DB_FILE = resolveDbPath();

    private static String resolveDbPath() {
        String custom = System.getenv("DOGMSG_DB_PATH");
        return (custom != null && !custom.isBlank()) ? custom : "dogmessenger.db";
    }

    private Connection conn;

    public synchronized void init() throws SQLException {
        System.out.println("Paso 1");
        try {
            Class.forName("org.sqlite.JDBC");
            System.out.println("Paso 2");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Driver SQLite no encontrado. Agrega sqlite-jdbc al classpath.", e);
        }

        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
            System.out.println("Paso 3");

            // busy_timeout (CAMBIO APLICADO)
            try (Statement st = conn.createStatement()) {
                System.out.println("ANTES busy_timeout");
                st.execute("PRAGMA busy_timeout=5000");
                System.out.println("DESPUES busy_timeout");
            }

            System.out.println("Paso 4");

            // WAL mode (CAMBIO APLICADO)
            try {
                try (Statement st = conn.createStatement()) {
                    System.out.println("ANTES WAL");
                    st.execute("PRAGMA journal_mode=WAL");
                    System.out.println("DESPUES WAL");
                }

                System.out.println("Paso 5");
                log.info("[DB] journal_mode=WAL activado.");

            } catch (SQLException walEx) {

                System.out.println("ERROR EN WAL");
                walEx.printStackTrace();

                log.warning("[DB] No se pudo activar WAL (" + walEx.getMessage()
                        + "). Usando journal_mode=DELETE como respaldo.");

                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=DELETE");
                }
            }

            // foreign_keys (CAMBIO APLICADO)
            try {
                System.out.println("ANTES FK");

                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA foreign_keys=ON");
                }

                System.out.println("DESPUES FK");

            } catch (Exception e) {
                System.out.println("ERROR EN FOREIGN_KEYS");
                e.printStackTrace();
                throw e;
            }

            System.out.println("Paso 6");

            createSchema();
            System.out.println("Paso 7");

            log.info("[DB] Base de datos inicializada: " + new File(DB_FILE).getAbsolutePath());

        } catch (SQLException e) {

            System.out.println("\n====================");
            System.out.println("SQL ORIGINAL:");
            System.out.println("====================");

            e.printStackTrace();

            if (e.getMessage() != null &&
                e.getMessage().contains("SQLITE_BUSY")) {

                throw new SQLException(
                    "La base de datos '" + DB_FILE + "' está bloqueada por otro proceso.\n",
                    e
                );
            }

            throw e;
        }
    }

    private void createSchema() throws SQLException {
        String[] ddl = {
            // ── Usuarios ──────────────────────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS users (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                username      TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                salt          TEXT NOT NULL,
                created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
            )""",

            // ── Sesiones activas (multi-dispositivo) ──────────────────────────
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id     INTEGER REFERENCES users(id),
                token       TEXT UNIQUE NOT NULL,
                device_type TEXT CHECK(device_type IN ('desktop','mobile')),
                created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
                last_ping   DATETIME
            )""",

            // ── Mensajes ──────────────────────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS messages (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                sender_id   INTEGER REFERENCES users(id),
                receiver_id INTEGER,
                is_group    BOOLEAN DEFAULT 0,
                msg_type    TEXT CHECK(msg_type IN ('text','image','file')),
                content     BLOB,
                status      TEXT DEFAULT 'sent'
                            CHECK(status IN ('sent','delivered','read')),
                timestamp   DATETIME DEFAULT CURRENT_TIMESTAMP
            )""",

            // ── Grupos ────────────────────────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS groups (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT NOT NULL,
                admin_id   INTEGER REFERENCES users(id),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )""",

            // ── Miembros de grupo ─────────────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS group_members (
                group_id  INTEGER REFERENCES groups(id),
                user_id   INTEGER REFERENCES users(id),
                joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (group_id, user_id)
            )""",

            // ── Cola de mensajes offline ──────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS offline_queue (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id     INTEGER REFERENCES users(id),
                packet_data BLOB NOT NULL,
                created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
            )""",

            // ── Archivos transferidos ─────────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS files (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id   INTEGER REFERENCES messages(id),
                filename     TEXT NOT NULL,
                mime_type    TEXT,
                size_bytes   INTEGER,
                storage_path TEXT,
                uploaded_at  DATETIME DEFAULT CURRENT_TIMESTAMP
            )""",

            // ── Claves públicas DH (para E2E) ─────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS public_keys (
                user_id    INTEGER REFERENCES users(id),
                public_key BLOB NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (user_id)
            )""",

            // ── Índices para búsquedas frecuentes ─────────────────────────────
            "CREATE INDEX IF NOT EXISTS idx_messages_sender   ON messages(sender_id)",
            "CREATE INDEX IF NOT EXISTS idx_messages_receiver ON messages(receiver_id)",
            "CREATE INDEX IF NOT EXISTS idx_messages_ts       ON messages(timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_sessions_user     ON sessions(user_id)",
            "CREATE INDEX IF NOT EXISTS idx_offline_user      ON offline_queue(user_id)"
        };

        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
        log.info("[DB] Esquema creado/verificado.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // USUARIOS
    // ════════════════════════════════════════════════════════════════════════

    /** Crea un usuario. Devuelve el ID generado o -1 si ya existe. */
    public synchronized int createUser(String username, String passwordHash, String salt)
            throws SQLException {
        String sql = "INSERT INTO users(username,password_hash,salt) VALUES(?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, salt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE")) return -1; // usuario ya existe
            throw e;
        }
    }

    /**
     * Busca un usuario por nombre.
     * @return array [id, passwordHash, salt] o null si no existe.
     */
    public synchronized String[] findUser(String username) throws SQLException {
        String sql = "SELECT id, password_hash, salt FROM users WHERE username=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{
                    String.valueOf(rs.getInt("id")),
                    rs.getString("password_hash"),
                    rs.getString("salt")
                };
            }
        }
    }

    public synchronized String getUsernameById(int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT username FROM users WHERE id=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("username") : null;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SESIONES
    // ════════════════════════════════════════════════════════════════════════

    /** Crea una sesión y devuelve el token generado. */
    public synchronized void createSession(int userId, String token, String deviceType)
            throws SQLException {
        String sql = "INSERT INTO sessions(user_id,token,device_type,last_ping)" +
                     "VALUES(?,?,?,CURRENT_TIMESTAMP)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, token);
            ps.setString(3, deviceType);
            ps.executeUpdate();
        }
    }

    /**
     * Valida un token de sesión.
     * @return userId si el token es válido, -1 si no existe.
     */
    public synchronized int validateToken(String token) throws SQLException {
        String sql = "SELECT user_id FROM sessions WHERE token=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("user_id") : -1;
            }
        }
    }

    /** Actualiza el timestamp de último ping de una sesión. */
    public synchronized void updatePing(String token) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sessions SET last_ping=CURRENT_TIMESTAMP WHERE token=?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        }
    }

    /** Elimina una sesión (logout). */
    public synchronized void deleteSession(String token) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM sessions WHERE token=?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        }
    }

    /** Elimina sesiones inactivas hace más de {@code seconds} segundos. */
    public synchronized int purgeExpiredSessions(int seconds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM sessions WHERE last_ping < datetime('now',?)" )) {
            ps.setString(1, "-" + seconds + " seconds");
            return ps.executeUpdate();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MENSAJES
    // ════════════════════════════════════════════════════════════════════════

    /** Persiste un mensaje y devuelve su ID generado. */
    public synchronized long saveMessage(int senderId, int receiverId, boolean isGroup,
                                         String type, byte[] content) throws SQLException {
        String sql = "INSERT INTO messages(sender_id,receiver_id,is_group,msg_type,content)" +
                     "VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, senderId);
            ps.setInt(2, receiverId);
            ps.setBoolean(3, isGroup);
            ps.setString(4, type);
            ps.setBytes(5, content);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    /** Actualiza el estado de un mensaje (sent → delivered → read). */
    public synchronized void updateMessageStatus(long msgId, String status)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE messages SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setLong(2, msgId);
            ps.executeUpdate();
        }
    }

    /**
     * Devuelve el historial de mensajes entre dos usuarios (o de un grupo).
     * @return ResultSet con columnas: id, sender_id, receiver_id, msg_type, content, status, timestamp
     */
    public synchronized ResultSet getMessageHistory(int userA, int userB, int limit)
            throws SQLException {
        String sql = """
            SELECT id, sender_id, receiver_id, msg_type, content, status, timestamp
            FROM messages
            WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)
            ORDER BY timestamp ASC
            LIMIT ?
            """;
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setInt(1, userA); ps.setInt(2, userB);
        ps.setInt(3, userB); ps.setInt(4, userA);
        ps.setInt(5, limit);
        return ps.executeQuery();
    }

    public synchronized ResultSet getGroupMessageHistory(int groupId, int limit)
            throws SQLException {
        String sql = """
            SELECT id, sender_id, receiver_id, msg_type, content, status, timestamp
            FROM messages
            WHERE receiver_id=? AND is_group=1
            ORDER BY timestamp ASC LIMIT ?
            """;
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setInt(1, groupId); ps.setInt(2, limit);
        return ps.executeQuery();
    }

    // ════════════════════════════════════════════════════════════════════════
    // GRUPOS
    // ════════════════════════════════════════════════════════════════════════

    /** Crea un grupo y devuelve su ID. */
    public synchronized int createGroup(String name, int adminId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO groups(name,admin_id) VALUES(?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setInt(2, adminId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                int gid = rs.next() ? rs.getInt(1) : -1;
                if (gid > 0) addGroupMember(gid, adminId); // creador es miembro
                return gid;
            }
        }
    }

    /** Agrega un miembro a un grupo. */
    public synchronized void addGroupMember(int groupId, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO group_members(group_id,user_id) VALUES(?,?)")) {
            ps.setInt(1, groupId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    /** Elimina un miembro de un grupo. */
    public synchronized void removeGroupMember(int groupId, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM group_members WHERE group_id=? AND user_id=?")) {
            ps.setInt(1, groupId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Devuelve los IDs de miembros de un grupo.
     * @return ResultSet con columna user_id
     */
    public synchronized ResultSet getGroupMembers(int groupId) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id FROM group_members WHERE group_id=?");
        ps.setInt(1, groupId);
        return ps.executeQuery();
    }

    /** ¿Es el usuario admin del grupo? */
    public synchronized boolean isGroupAdmin(int groupId, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM groups WHERE id=? AND admin_id=?")) {
            ps.setInt(1, groupId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // COLA OFFLINE
    // ════════════════════════════════════════════════════════════════════════

    /** Encola un paquete serializado para un usuario offline. */
    public synchronized void enqueueOffline(int userId, byte[] packetData) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO offline_queue(user_id,packet_data) VALUES(?,?)")) {
            ps.setInt(1, userId);
            ps.setBytes(2, packetData);
            ps.executeUpdate();
        }
    }

    /**
     * Devuelve todos los paquetes encolados para un usuario (en orden cronológico).
     * @return ResultSet con columnas: id, packet_data
     */
    public synchronized ResultSet getOfflineQueue(int userId) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "SELECT id, packet_data FROM offline_queue WHERE user_id=? ORDER BY created_at ASC");
        ps.setInt(1, userId);
        return ps.executeQuery();
    }

    /** Elimina un elemento de la cola (tras entrega confirmada). */
    public synchronized void dequeueOffline(long queueId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM offline_queue WHERE id=?")) {
            ps.setLong(1, queueId);
            ps.executeUpdate();
        }
    }

    /** Elimina TODOS los mensajes encolados de un usuario. */
    public synchronized void clearOfflineQueue(int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM offline_queue WHERE user_id=?")) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARCHIVOS
    // ════════════════════════════════════════════════════════════════════════

    /** Registra metadatos de un archivo transferido. */
    public synchronized long saveFileMetadata(long messageId, String filename,
                                               String mimeType, long sizeBytes,
                                               String storagePath) throws SQLException {
        String sql = "INSERT INTO files(message_id,filename,mime_type,size_bytes,storage_path)" +
                     "VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, messageId);
            ps.setString(2, filename);
            ps.setString(3, mimeType);
            ps.setLong(4, sizeBytes);
            ps.setString(5, storagePath);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CLAVES PÚBLICAS DH
    // ════════════════════════════════════════════════════════════════════════

    /** Almacena o actualiza la clave pública DH de un usuario. */
    public synchronized void storePublicKey(int userId, byte[] publicKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO public_keys(user_id,public_key,updated_at)" +
                "VALUES(?,?,CURRENT_TIMESTAMP)")) {
            ps.setInt(1, userId);
            ps.setBytes(2, publicKey);
            ps.executeUpdate();
        }
    }

    /** Devuelve la clave pública DH de un usuario, o null. */
    public synchronized byte[] getPublicKey(int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT public_key FROM public_keys WHERE user_id=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes("public_key") : null;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cierre
    // ════════════════════════════════════════════════════════════════════════

    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                // Forzar checkpoint del WAL: fusiona dogmessenger.db-wal dentro
                // de dogmessenger.db y elimina los archivos auxiliares -wal/-shm.
                // Esto evita que queden archivos huérfanos que puedan confundirse
                // con un bloqueo real en el siguiente arranque.
                try {
                    conn.createStatement().execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // Si falla el checkpoint no es crítico; el close() de abajo
                    // libera el lock de todas formas.
                }
                conn.close();
                log.info("[DB] Conexión cerrada.");
            }
        } catch (SQLException e) {
            log.warning("[DB] Error al cerrar: " + e.getMessage());
        }
    }
}