package dogmsg;

import dogmsg.protocol.OpCode;
import dogmsg.protocol.Packet;
import dogmsg.protocol.PacketParser;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * ClientHandler.java
 * Dog Messenger — Hilo dedicado por cada conexión TCP de cliente.
 *
 * Ciclo de vida:
 *  1. El cliente DEBE enviar AUTH_REQUEST como primer paquete.
 *     Si falla → se cierra la conexión.
 *  2. Loop principal: leer Packet → dispatch según opcode.
 *  3. DISCONNECT o error de red → cleanup y cierre.
 *
 * Rate limiting (sección 10.3):
 *  Máximo 100 paquetes/segundo por cliente. Si se supera, se descarta
 *  el paquete y se loguea un warning.
 *
 * Thread-safety:
 *  sendPacket() y sendRawBytes() están sincronizados sobre el OutputStream.
 *  El campo running usa volatile para visibilidad entre hilos.
 */
public class ClientHandler implements Runnable {

    private static final Logger log = Logger.getLogger(ClientHandler.class.getName());

    // ── Dependencias ──────────────────────────────────────────────────────────
    private final Socket                    socket;
    private final Map<Integer, ClientHandler> onlineMap;
    private final AuthManager               auth;
    private final MessageRouter             router;
    private final GroupManager              groupManager;
    private final FileTransferHandler       fileTransfer;
    private final OfflineQueue              offline;
    private final EncryptionBroker          encBroker;
    private final QRManager                 qrManager;

    // ── Estado de conexión ────────────────────────────────────────────────────
    private InputStream    rawIn;
    private OutputStream   rawOut;
    private final Object   writeLock = new Object();
    private volatile boolean running = false;

    // ── Estado del usuario autenticado ────────────────────────────────────────
    private int    userId   = -1;
    private String token    = null;
    private String username = null;

    // ── Rate limiting: paquetes en el segundo actual ───────────────────────────
    private final AtomicInteger pktsThisSec = new AtomicInteger(0);
    private long lastSecondTs = System.currentTimeMillis() / 1000;
    private static final int   MAX_PKTS_PER_SEC = 100;

    // ── Secuencia de paquetes ─────────────────────────────────────────────────
    private int outSeq = 0;

    public ClientHandler(Socket socket,
                         Map<Integer, ClientHandler> onlineMap,
                         AuthManager auth,
                         MessageRouter router,
                         GroupManager groupManager,
                         FileTransferHandler fileTransfer,
                         OfflineQueue offline,
                         EncryptionBroker encBroker,
                         QRManager qrManager) {
        this.socket       = socket;
        this.onlineMap    = onlineMap;
        this.auth         = auth;
        this.router       = router;
        this.groupManager = groupManager;
        this.fileTransfer = fileTransfer;
        this.offline      = offline;
        this.encBroker    = encBroker;
        this.qrManager    = qrManager;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Runnable
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void run() {
        try {
            socket.setSoTimeout(90_000); // 90s sin actividad → timeout (sección 8.1)
            rawIn  = new BufferedInputStream(socket.getInputStream());
            rawOut = new BufferedOutputStream(socket.getOutputStream());
            running = true;

            // ── 1. Autenticación obligatoria ────────────────────────────────────
            if (!handleAuth()) {
                log.info("[CH] Auth fallida desde " + socket.getInetAddress());
                return;
            }

            log.info("[CH] Cliente autenticado: " + username + " (id=" + userId + ")");

            // Registrar en mapa de online
            onlineMap.put(userId, this);

            // Entregar mensajes offline pendientes
            int pending = offline.pendingCount(userId);
            if (pending > 0) {
                log.info("[CH] Entregando " + pending + " msgs offline a " + username);
                offline.flushToClient(userId, this);
            }

            // ── 2. Loop principal ───────────────────────────────────────────────
            while (running) {
                Packet pkt = PacketParser.read(rawIn);
                if (!rateLimitCheck()) {
                    log.warning("[CH] Rate limit excedido para " + username);
                    continue;
                }
                dispatch(pkt);
            }

        } catch (java.net.SocketTimeoutException e) {
            log.info("[CH] Timeout de inactividad: " + username);
        } catch (EOFException | java.net.SocketException e) {
            if (running) log.info("[CH] Conexión cerrada: " + username);
        } catch (IOException e) {
            if (running) log.warning("[CH] I/O error (" + username + "): " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Autenticación (primer paquete obligatorio)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Espera AUTH_REQUEST, valida credenciales y responde con AUTH_RESPONSE.
     *
     * Payload AUTH_REQUEST JSON:
     *   {"action":"login"|"register","username":"...","password_hash":"...","device_type":"..."}
     *
     * @return true si la autenticación fue exitosa
     */
    private boolean handleAuth() throws IOException {
        Packet pkt;
        try {
            pkt = PacketParser.read(rawIn);
        } catch (IOException e) {
            return false;
        }

        if (pkt.opcode != OpCode.AUTH_REQUEST) {
            sendAuthError("Se esperaba AUTH_REQUEST como primer paquete");
            return false;
        }

        String json      = pkt.getPayloadAsString();
        String action    = extractJson(json, "action");
        String uname     = extractJson(json, "username");
        String pHash     = extractJson(json, "password_hash");
        String devType   = extractJsonOpt(json, "device_type");

        AuthManager.AuthResult result;
        if ("register".equalsIgnoreCase(action)) {
            result = auth.register(uname, pHash, devType);
        } else {
            result = auth.login(uname, pHash, devType);
        }

        // Responder al cliente
        Packet resp = new Packet(OpCode.AUTH_RESPONSE, 0, -1, result.toJson());
        resp.receiverId = result.ok ? result.userId : pkt.senderId;
        sendPacket(resp);

        if (result.ok) {
            this.userId   = result.userId;
            this.username = result.username;
            this.token    = result.token;
        }
        return result.ok;
    }

    private void sendAuthError(String msg) throws IOException {
        String json = "{\"ok\":false,\"error\":\"" + msg + "\"}";
        Packet err = new Packet(OpCode.AUTH_RESPONSE, 0, 0, json);
        sendPacket(err);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Despacho de paquetes
    // ════════════════════════════════════════════════════════════════════════

    private void dispatch(Packet pkt) {
        // Sobrescribir senderId con el userId autenticado (seguridad)
        pkt.senderId = this.userId;

        switch (pkt.opcode) {
            // ── Mensajes privados ──────────────────────────────────────────────
            case MSG_TEXT, MSG_IMAGE -> router.routePrivate(pkt);

            // ── Archivo (metadata) ────────────────────────────────────────────
            case MSG_FILE    -> fileTransfer.handleMetadata(pkt);

            // ── Chunks de archivo ─────────────────────────────────────────────
            case FILE_CHUNK  -> fileTransfer.handleChunk(pkt);
            case FILE_COMPLETE -> fileTransfer.handleComplete(pkt);

            // ── Grupos ────────────────────────────────────────────────────────
            case GROUP_MSG   -> router.routeGroup(pkt);
            case GROUP_CREATE -> handleGroupCreate(pkt);
            case GROUP_JOIN   -> handleGroupJoin(pkt);
            case GROUP_LEAVE  -> handleGroupLeave(pkt);

            // ── QR Clonación ──────────────────────────────────────────────────
            case QR_GENERATE -> qrManager.handleGenerateRequest(pkt, this);
            case QR_VALIDATE -> qrManager.handleValidateRequest(pkt, this, router);

            // ── Encriptación E2E ──────────────────────────────────────────────
            case KEY_EXCHANGE -> encBroker.handleKeyExchange(pkt, onlineMap);

            // ── Keep-alive ────────────────────────────────────────────────────
            case PING -> {
                auth.refreshPing(token);
                Packet pong = new Packet(OpCode.PING, 0, userId, (byte[]) null);
                pong.opcode = OpCode.PING;  // PONG reutiliza el mismo opcode
                sendPacket(pong);
            }

            // ── Desconexión limpia ────────────────────────────────────────────
            case DISCONNECT -> {
                running = false;
                log.info("[CH] DISCONNECT recibido de " + username);
            }

            default -> log.warning("[CH] OpCode no manejado: " + pkt.opcode);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Handlers de grupos
    // ════════════════════════════════════════════════════════════════════════

    private void handleGroupCreate(Packet pkt) {
        // Payload JSON: {"name":"Mi Grupo"}
        String name = extractJsonOpt(pkt.getPayloadAsString(), "name");
        if (name.isBlank()) { sendError("Nombre de grupo vacío"); return; }
        int gid = groupManager.createGroup(name, userId);
        String resp = gid > 0
                ? String.format("{\"ok\":true,\"group_id\":%d,\"name\":\"%s\"}", gid, name)
                : "{\"ok\":false,\"error\":\"No se pudo crear el grupo\"}";
        sendPacket(new Packet(OpCode.MSG_ACK, 0, userId, resp));
    }

    private void handleGroupJoin(Packet pkt) {
        // Payload JSON: {"group_id":N,"target_user_id":M}
        String json   = pkt.getPayloadAsString();
        int groupId   = Integer.parseInt(extractJson(json, "group_id"));
        int targetId  = Integer.parseInt(extractJsonOpt(json, "target_user_id").isEmpty()
                ? String.valueOf(userId) : extractJsonOpt(json, "target_user_id"));
        boolean ok = groupManager.addMember(groupId, targetId, userId);
        sendPacket(new Packet(OpCode.MSG_ACK, 0, userId,
                ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"Sin permisos o grupo no existe\"}"));
    }

    private void handleGroupLeave(Packet pkt) {
        // Payload JSON: {"group_id":N,"target_user_id":M}
        String json  = pkt.getPayloadAsString();
        int groupId  = Integer.parseInt(extractJson(json, "group_id"));
        String tStr  = extractJsonOpt(json, "target_user_id");
        int targetId = tStr.isEmpty() ? userId : Integer.parseInt(tStr);
        boolean ok = groupManager.removeMember(groupId, targetId, userId);
        sendPacket(new Packet(OpCode.MSG_ACK, 0, userId,
                ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"Sin permisos o no eres miembro\"}"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Envío al cliente (thread-safe)
    // ════════════════════════════════════════════════════════════════════════

    /** Serializa y envía un Packet al cliente. Asigna número de secuencia. */
    public void sendPacket(Packet pkt) {
        synchronized (writeLock) {
            if (rawOut == null || socket.isClosed()) return;
            try {
                pkt.sequence = ++outSeq;
                rawOut.write(pkt.serialize());
                rawOut.flush();
            } catch (IOException e) {
                log.warning("[CH] Error enviando paquete a " + username + ": " + e.getMessage());
                running = false;
            }
        }
    }

    /** Envía bytes crudos (para entrega de mensajes offline ya serializados). */
    public void sendRawBytes(byte[] data) {
        synchronized (writeLock) {
            if (rawOut == null || socket.isClosed() || data == null) return;
            try {
                rawOut.write(data);
                rawOut.flush();
            } catch (IOException e) {
                log.warning("[CH] Error enviando bytes a " + username + ": " + e.getMessage());
                running = false;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Rate limiting
    // ════════════════════════════════════════════════════════════════════════

    private boolean rateLimitCheck() {
        long nowSec = System.currentTimeMillis() / 1000;
        if (nowSec != lastSecondTs) {
            lastSecondTs = nowSec;
            pktsThisSec.set(0);
        }
        return pktsThisSec.incrementAndGet() <= MAX_PKTS_PER_SEC;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cleanup
    // ════════════════════════════════════════════════════════════════════════

    private void cleanup() {
        running = false;
        if (userId > 0) {
            onlineMap.remove(userId, this);
            auth.logout(token);
            log.info("[CH] " + username + " desconectado. Online=" + onlineMap.size());
        }
        try { socket.close(); } catch (IOException ignored) {}
    }

    /** Cierre forzado desde el exterior (shutdown del servidor). */
    public void forceClose() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private void sendError(String msg) {
        sendPacket(new Packet(OpCode.MSG_ACK, 0, userId,
                "{\"ok\":false,\"error\":\"" + msg + "\"}"));
    }

    public int    getUserId()   { return userId; }
    public String getUsername() { return username; }

    private static String extractJson(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) throw new IllegalArgumentException("Clave JSON no encontrada: " + key);
        int start = idx + search.length();
        boolean isStr = json.charAt(start) == '"';
        if (isStr) {
            start++;
            return json.substring(start, json.indexOf('"', start));
        } else {
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            return json.substring(start, end).trim();
        }
    }

    private static String extractJsonOpt(String json, String key) {
        try { return extractJson(json, key); }
        catch (Exception e) { return ""; }
    }
}
