package dogmsg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * AuthManager.java
 * Dog Messenger — Registro, login y validación de sesiones.
 *
 * Seguridad (sección 10.2 del plan):
 *  - Contraseñas: SHA-256(password + salt). Salt único por usuario (16 bytes aleatorios).
 *  - Las contraseñas NUNCA viajan en texto plano: el cliente las hashea antes de enviar.
 *    → El servidor recibe SHA-256(password) y almacena SHA-256(SHA-256(password) + salt).
 *  - Tokens de sesión: UUID v4, expiran a los 24h sin ping.
 *  - Multi-dispositivo: un usuario puede tener N sesiones activas simultáneamente.
 *
 * Payload JSON de AUTH_REQUEST:
 *   {"action":"login"|"register", "username":"...", "password_hash":"...", "device_type":"desktop"|"mobile"}
 *
 * Respuesta AUTH_RESPONSE payload JSON:
 *   Éxito: {"ok":true,  "token":"...", "user_id":123, "username":"..."}
 *   Error: {"ok":false, "error":"mensaje de error"}
 */
public class AuthManager {

    private static final Logger log = Logger.getLogger(AuthManager.class.getName());

    /** Mapa en memoria: token → userId (caché rápida para validar tokens sin ir a BD). */
    private final Map<String, Integer> tokenCache = new ConcurrentHashMap<>();

    private final DatabaseManager db;
    private final SecureRandom    rng = new SecureRandom();

    public AuthManager(DatabaseManager db) {
        this.db = db;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Registro
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Registra un nuevo usuario.
     *
     * @param username     nombre de usuario (único)
     * @param passwordHash SHA-256 de la contraseña enviada por el cliente
     * @param deviceType   "desktop" o "mobile"
     * @return AuthResult con token si OK, o error si el username ya existe
     */
    public AuthResult register(String username, String passwordHash, String deviceType) {
        if (username == null || username.isBlank())
            return AuthResult.error("Nombre de usuario vacío");
        if (passwordHash == null || passwordHash.length() < 8)
            return AuthResult.error("Hash de contraseña inválido");

        try {
            // Generar salt único
            byte[] saltBytes = new byte[16];
            rng.nextBytes(saltBytes);
            String salt = bytesToHex(saltBytes);

            // Hash final almacenado = SHA-256(clientHash + salt)
            String storedHash = sha256(passwordHash + salt);

            int userId = db.createUser(username, storedHash, salt);
            if (userId < 0) {
                return AuthResult.error("El nombre de usuario '" + username + "' ya está en uso");
            }

            // Crear sesión
            String token = UUID.randomUUID().toString();
            db.createSession(userId, token, normalizeDeviceType(deviceType));
            tokenCache.put(token, userId);

            log.info("[Auth] Usuario registrado: " + username + " (id=" + userId + ")");
            return AuthResult.ok(userId, username, token);

        } catch (Exception e) {
            log.severe("[Auth] Error en registro: " + e.getMessage());
            return AuthResult.error("Error interno del servidor");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Login
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Autentica un usuario existente.
     *
     * @param username     nombre de usuario
     * @param passwordHash SHA-256 de la contraseña (enviado por el cliente)
     * @param deviceType   "desktop" o "mobile"
     * @return AuthResult con token si OK, o error si credenciales inválidas
     */
    public AuthResult login(String username, String passwordHash, String deviceType) {
        try {
            String[] row = db.findUser(username);
            if (row == null) {
                return AuthResult.error("Usuario no encontrado");
            }

            int    userId       = Integer.parseInt(row[0]);
            String storedHash   = row[1];
            String salt         = row[2];

            // Verificar: SHA-256(clientHash + salt) debe coincidir con storedHash
            String computed = sha256(passwordHash + salt);
            if (!computed.equals(storedHash)) {
                return AuthResult.error("Contraseña incorrecta");
            }

            // Crear nueva sesión (multi-dispositivo permitido)
            String token = UUID.randomUUID().toString();
            db.createSession(userId, token, normalizeDeviceType(deviceType));
            tokenCache.put(token, userId);

            log.info("[Auth] Login exitoso: " + username + " (id=" + userId
                    + ", device=" + deviceType + ")");
            return AuthResult.ok(userId, username, token);

        } catch (Exception e) {
            log.severe("[Auth] Error en login: " + e.getMessage());
            return AuthResult.error("Error interno del servidor");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Validación de token
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Valida un token de sesión.
     * Primero consulta el caché en memoria; si no está, va a la BD.
     *
     * @return userId si válido, -1 si no existe o expirado
     */
    public int validateToken(String token) {
        if (token == null) return -1;

        // Caché rápida
        Integer cached = tokenCache.get(token);
        if (cached != null) return cached;

        // Fallback a BD (p.ej. tras reinicio del servidor)
        try {
            int userId = db.validateToken(token);
            if (userId > 0) tokenCache.put(token, userId);
            return userId;
        } catch (SQLException e) {
            log.warning("[Auth] Error validando token: " + e.getMessage());
            return -1;
        }
    }

    /** Actualiza el timestamp de último ping de una sesión. */
    public void refreshPing(String token) {
        try {
            db.updatePing(token);
        } catch (SQLException e) {
            log.warning("[Auth] Error actualizando ping: " + e.getMessage());
        }
    }

    /** Cierra una sesión (logout). */
    public void logout(String token) {
        tokenCache.remove(token);
        try {
            db.deleteSession(token);
            log.info("[Auth] Sesión cerrada (token=" + token.substring(0, 8) + "...)");
        } catch (SQLException e) {
            log.warning("[Auth] Error cerrando sesión: " + e.getMessage());
        }
    }

    /** Elimina sesiones inactivas (sin ping en las últimas 24h). */
    public void purgeExpiredSessions() {
        try {
            int removed = db.purgeExpiredSessions(86400); // 24 horas
            if (removed > 0) {
                log.info("[Auth] Sesiones expiradas eliminadas: " + removed);
                // Limpiar caché en memoria (aproximado; en prod usar referencias inversas)
                tokenCache.clear();
            }
        } catch (SQLException e) {
            log.warning("[Auth] Error purgando sesiones: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 no disponible", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String normalizeDeviceType(String dt) {
        if (dt == null) return "desktop";
        return dt.equalsIgnoreCase("mobile") ? "mobile" : "desktop";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Clase resultado
    // ════════════════════════════════════════════════════════════════════════

    public static class AuthResult {
        public final boolean ok;
        public final int     userId;
        public final String  username;
        public final String  token;
        public final String  error;

        private AuthResult(boolean ok, int userId, String username,
                           String token, String error) {
            this.ok       = ok;
            this.userId   = userId;
            this.username = username;
            this.token    = token;
            this.error    = error;
        }

        public static AuthResult ok(int userId, String username, String token) {
            return new AuthResult(true, userId, username, token, null);
        }

        public static AuthResult error(String msg) {
            return new AuthResult(false, -1, null, null, msg);
        }

        /** Serializa a JSON para el payload de AUTH_RESPONSE. */
        public String toJson() {
            if (ok) {
                return String.format(
                    "{\"ok\":true,\"token\":\"%s\",\"user_id\":%d,\"username\":\"%s\"}",
                    token, userId, username);
            } else {
                return String.format("{\"ok\":false,\"error\":\"%s\"}", error);
            }
        }
    }
}
