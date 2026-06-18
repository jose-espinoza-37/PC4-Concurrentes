package dogmsg;

import dogmsg.protocol.OpCode;
import dogmsg.protocol.Packet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * QRManager.java
 * Dog Messenger — Gestión de tokens QR para clonación de sesiones.
 *
 * Flujo (sección 7.1 — T-A8 del plan):
 *
 *  1. QR_GENERATE (cliente A → servidor):
 *     - Servidor genera token temporal (UUID, expira en 60 segundos).
 *     - Responde a A con el token en payload.
 *     - A genera el QR con ese token y lo muestra en pantalla.
 *
 *  2. QR_VALIDATE (cliente B → servidor, con token escaneado):
 *     - Servidor valida el token.
 *     - Vincula la sesión de B como dispositivo adicional del usuario de A.
 *     - Envía HISTORY_SYNC a B con el historial completo del usuario.
 *     - Invalida el token (uso único).
 *
 * Ambos dispositivos quedan activos simultáneamente, recibiendo mensajes en paralelo.
 */
public class QRManager {

    private static final Logger log = Logger.getLogger(QRManager.class.getName());

    private static final long TOKEN_TTL_MS = 60_000; // 60 segundos

    /** Entrada del mapa de tokens activos. */
    private static class TokenEntry {
        final int    ownerUserId;
        final long   expiresAt;

        TokenEntry(int ownerUserId) {
            this.ownerUserId = ownerUserId;
            this.expiresAt   = System.currentTimeMillis() + TOKEN_TTL_MS;
        }

        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /** token → TokenEntry */
    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qr-token-cleaner");
                t.setDaemon(true);
                return t;
            });

    public QRManager() {
        // Purgar tokens expirados cada 30 segundos
        cleaner.scheduleAtFixedRate(this::purgeExpired, 30, 30, TimeUnit.SECONDS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // QR_GENERATE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Genera un token QR para el usuario solicitante y lo envía de vuelta.
     *
     * @param pkt     paquete QR_GENERATE recibido
     * @param handler ClientHandler del solicitante (para enviar la respuesta)
     */
    public void handleGenerateRequest(Packet pkt, ClientHandler handler) {
        int userId = pkt.senderId;

        // Revocar tokens previos del mismo usuario
        tokens.entrySet().removeIf(e -> e.getValue().ownerUserId == userId);

        String token = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        tokens.put(token, new TokenEntry(userId));

        log.info("[QR] Token generado para userId=" + userId
                + " expira en " + (TOKEN_TTL_MS / 1000) + "s");

        // Responder con el token
        // Payload JSON: {"token":"...","expires_in_seconds":60}
        String payload = String.format(
                "{\"token\":\"%s\",\"expires_in_seconds\":%d}",
                token, TOKEN_TTL_MS / 1000);

        Packet resp  = new Packet(OpCode.QR_GENERATE, 0, userId, payload);
        handler.sendPacket(resp);
    }

    // ════════════════════════════════════════════════════════════════════════
    // QR_VALIDATE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Valida un token QR escaneado por el cliente B y, si es válido,
     * envía el historial del usuario original a B.
     *
     * Payload de QR_VALIDATE JSON: {"token":"...","device_type":"desktop"|"mobile"}
     *
     * @param pkt     paquete QR_VALIDATE recibido del cliente que escaneó
     * @param handler ClientHandler del cliente que escaneó (B)
     * @param router  MessageRouter para construir el HISTORY_SYNC
     */
    public void handleValidateRequest(Packet pkt, ClientHandler handler, MessageRouter router) {
        String json  = pkt.getPayloadAsString();
        String token = extractJson(json, "token");

        TokenEntry entry = tokens.get(token);

        if (entry == null) {
            log.warning("[QR] Token desconocido: " + token);
            sendValidationResult(handler, false, "Token QR inválido o inexistente");
            return;
        }

        if (entry.isExpired()) {
            tokens.remove(token);
            log.warning("[QR] Token expirado: " + token);
            sendValidationResult(handler, false, "Token QR expirado. Solicita uno nuevo.");
            return;
        }

        int originUserId = entry.ownerUserId;
        int scannerUserId = pkt.senderId;

        log.info("[QR] userId=" + scannerUserId + " clonando sesión de userId=" + originUserId);

        // Invalidar token (uso único)
        tokens.remove(token);

        // Notificar éxito al cliente B
        sendValidationResult(handler, true,
                "Sesión clonada. Sincronizando historial...");

        // Enviar historial al cliente B
        Packet history = router.buildHistorySync(originUserId, scannerUserId);
        handler.sendPacket(history);

        log.info("[QR] HISTORY_SYNC enviado a userId=" + scannerUserId);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void sendValidationResult(ClientHandler handler, boolean ok, String msg) {
        String payload = String.format(
                "{\"ok\":%s,\"message\":\"%s\"}", ok, msg);
        Packet resp = new Packet(OpCode.QR_VALIDATE, 0, handler.getUserId(), payload);
        handler.sendPacket(resp);
    }

    private void purgeExpired() {
        int before = tokens.size();
        tokens.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - tokens.size();
        if (removed > 0) log.info("[QR] Tokens expirados purgados: " + removed);
    }

    public void shutdown() { cleaner.shutdownNow(); }

    private static String extractJson(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int start = idx + search.length();
        if (json.charAt(start) == '"') {
            start++;
            return json.substring(start, json.indexOf('"', start));
        }
        int end = json.indexOf(',', start);
        if (end < 0) end = json.indexOf('}', start);
        return json.substring(start, end).trim();
    }
}
