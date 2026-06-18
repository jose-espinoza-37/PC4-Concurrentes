package dogmsg.client;

import dogmsg.client.protocol.Json;
import dogmsg.client.protocol.OpCode;
import dogmsg.client.protocol.Packet;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logica central del cliente (T-B). Orquesta:
 * <ul>
 *   <li>{@link SocketManager} para la conexion TCP.</li>
 *   <li>{@link CryptoManager} por conversacion para el cifrado E2E.</li>
 *   <li>{@link LocalCache} para el cache local de mensajes.</li>
 * </ul>
 *
 * <p>Recibe los paquetes entrantes, los descifra/persiste y notifica a la UI
 * en el hilo de Swing (EDT). La UI invoca los metodos {@code login},
 * {@code sendText}, etc.</p>
 */
public class ChatController implements SocketManager.Listener {

    /** Hooks que implementa la UI para recibir eventos. */
    public interface UiCallbacks {
        void onConnectionState(boolean connected, String detail);
        void onAuthResult(boolean ok, long userId, String tokenOrError);
        void onTextMessage(long peerId, boolean isGroup, long senderId, String text, long timestamp);
        void onAck(long sequence);
        void onAddedToGroup(long groupId, long byUserId, String groupName); // notificacion de GROUP_JOIN ajeno
        void onGroupCreated(long groupId, String name);   // ACK real de GROUP_CREATE propio
        void onQrToken(String token, int expiresInSeconds); // respuesta a QR_GENERATE
        void onQrValidated(boolean ok, String message);     // respuesta a QR_VALIDATE
        void onHistorySynced(int count);
        void onSystem(String message);
    }

    private final String deviceType;
    private final SocketManager socket;
    private final LocalCache cache;
    private UiCallbacks ui;

    // Una sesion de cifrado por contraparte (userId).
    private final Map<Long, CryptoManager> cryptoByPeer = new ConcurrentHashMap<>();
    // Mensajes en espera de clave compartida (la spec exige encrypted=1 siempre
    // en MSG_TEXT; nunca se envia texto plano por la red).
    private final Map<Long, java.util.List<String>> pendingByPeer = new ConcurrentHashMap<>();
    // Miembros (IDs) pendientes de agregar al grupo recien creado; se usan
    // cuando llega el MSG_ACK de GROUP_CREATE con el group_id real (ver
    // handleAck). Solo se admite una creacion de grupo en vuelo a la vez,
    // suficiente para el flujo actual de un solo dialogo modal de creacion.
    private volatile java.util.List<Long> pendingGroupMembers = null;

    // Credenciales retenidas tras un login/registro exitoso. Se necesitan para
    // autenticar TAMBIEN la conexion del puerto de archivos (ver authPayload()):
    // ClientHandler.java exige AUTH_REQUEST como primer paquete en CUALQUIER
    // conexion, incluida la del canal de archivos (Server.java: "mismo handler,
    // puerto separado"). Sin esto, FileChunker mandaba MSG_FILE/MSG_IMAGE como
    // primer paquete y el servidor lo rechazaba (userId=-1, sin sesion).
    private volatile String lastUsername;
    private volatile String lastPasswordHash;

    public ChatController(String host, int port, LocalCache cache, String deviceType) {
        this.deviceType = deviceType;
        this.cache = cache;
        this.socket = new SocketManager(host, port, this);
    }

    public void setUi(UiCallbacks ui) { this.ui = ui; }
    public void start() { socket.start(); }
    public void shutdown() { socket.shutdown(); }
    public long myUserId() { return socket.myUserId(); }

    // ----------------- Acciones desde la UI -----------------

    /** Envia AUTH_REQUEST (login o registro). La contrasena se hashea aqui. */
    public void authenticate(String username, String password, boolean register) {
        this.lastUsername = username;
        this.lastPasswordHash = CryptoManager.hashPassword(password);
        String payload = Json.obj()
                .put("username", username)
                .put("password_hash", lastPasswordHash)
                .put("action", register ? "register" : "login")
                .put("device_type", deviceType)
                .build();
        trySend(OpCode.AUTH_REQUEST, 0, 0, payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Construye el payload AUTH_REQUEST (accion "login") con las mismas
     * credenciales de la sesion actual. Lo usa {@link FileChunker} para
     * autenticar su propia conexion al puerto de archivos antes de mandar
     * MSG_FILE/MSG_IMAGE, tal como exige ClientHandler.handleAuth.
     *
     * @return el payload UTF-8 listo para enviar, o null si todavia no hay
     *         una sesion iniciada en este cliente.
     */
    public byte[] buildFileChannelAuthPayload() {
        if (lastUsername == null || lastPasswordHash == null) return null;
        String payload = Json.obj()
                .put("username", lastUsername)
                .put("password_hash", lastPasswordHash)
                .put("action", "login")
                .put("device_type", deviceType)
                .build();
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Envia un mensaje de texto cifrado E2E al peer.
     *
     * La spec (protocol_spec.md 3.3) exige flags.encrypted=1 siempre en
     * MSG_TEXT: el payload es [16 bytes IV][ciphertext]. Por eso NUNCA se
     * manda texto plano por la red: si aun no hay clave compartida, el
     * mensaje se encola localmente, se dispara KEY_EXCHANGE, y se vacia la
     * cola (flushPending) en cuanto la clave queda lista.
     *
     * NOTA GROUP_MSG: la spec (3.10) tambien exige encrypted=1 en mensajes
     * de grupo, pero EncryptionBroker.java solo implementa intercambio DH
     * 1-a-1 (un par de claves por par de usuarios); no hay un mecanismo de
     * clave de grupo documentado. Hasta que Persona A aclare como se cifra
     * un GROUP_MSG (¿clave compartida por todos los miembros? ¿cifrado
     * independiente por destinatario?), los mensajes de grupo se envian
     * SIN flag encrypted, fieles al unico mecanismo de cifrado que el
     * servidor realmente soporta hoy.
     */
    public void sendText(long peerId, boolean isGroup, String text) {
        if (isGroup) {
            sendGroupPlain(peerId, text);
            return;
        }
        
        // --- BYPASS PARA EL BOT DE VENTAS (ID 5) ---
        if (peerId == 5) {
            try {
                byte[] payload = text.getBytes(StandardCharsets.UTF_8);
                // Enviamos con flag 0 (sin encriptar)
                long seq = socket.sendPacket(OpCode.MSG_TEXT, peerId, 0, payload);
                cacheAsSentWithSeq(seq, peerId, false, text);
            } catch (Exception e) {
                notifySystem("Error al enviar al bot: " + e.getMessage());
            }
            return;
        }
        // -------------------------------------------

        try {
            CryptoManager crypto = cryptoByPeer.get(peerId);
            if (crypto != null && crypto.isReady()) {
                doSendEncrypted(peerId, text, crypto);
            } else {
                pendingByPeer.computeIfAbsent(peerId, k -> new java.util.ArrayList<>()).add(text);
                cacheAsSent(peerId, false, text); 
                startKeyExchange(peerId);
            }
        } catch (Exception e) {
            notifySystem("Error al enviar: " + e.getMessage());
        }
    }

    private void sendGroupPlain(long groupId, String text) {
        try {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            long seq = socket.sendPacket(OpCode.GROUP_MSG, groupId, Packet.FLAG_IS_GROUP, payload);
            cacheAsSentWithSeq(seq, groupId, true, text);
        } catch (Exception e) {
            notifySystem("Error al enviar al grupo: " + e.getMessage());
        }
    }

    private void doSendEncrypted(long peerId, String text, CryptoManager crypto) throws Exception {
        byte[] payload = crypto.encrypt(text.getBytes(StandardCharsets.UTF_8));
        long seq = socket.sendPacket(OpCode.MSG_TEXT, peerId, Packet.FLAG_ENCRYPTED, payload);
        cacheAsSentWithSeq(seq, peerId, false, text);
    }

    private void cacheAsSent(long peerId, boolean isGroup, String text) {
        cacheAsSentWithSeq(0, peerId, isGroup, text);
    }

    private void cacheAsSentWithSeq(long seq, long peerId, boolean isGroup, String text) {
        LocalCache.CachedMessage cm = new LocalCache.CachedMessage();
        cm.id = seq;
        cm.peerId = peerId;
        cm.isGroup = isGroup;
        cm.senderId = myUserId();
        cm.type = "text";
        cm.text = text;
        cm.timestamp = System.currentTimeMillis();
        cm.status = "sent";
        try {
            cache.insert(cm);
        } catch (java.sql.SQLException e) {
            notifySystem("Error guardando en cache local: " + e.getMessage());
        }
    }

    /** Envia todos los mensajes que quedaron en espera de clave para este peer. */
    private void flushPending(long peerId) {
        java.util.List<String> queued = pendingByPeer.remove(peerId);
        if (queued == null || queued.isEmpty()) return;
        CryptoManager crypto = cryptoByPeer.get(peerId);
        if (crypto == null || !crypto.isReady()) return;
        for (String text : queued) {
            try {
                doSendEncrypted(peerId, text, crypto);
            } catch (Exception e) {
                notifySystem("Error al enviar mensaje en espera: " + e.getMessage());
            }
        }
    }

    /** Inicia el intercambio de claves DH con un peer (T-B7). */
    public void startKeyExchange(long peerId) {
        try {
            CryptoManager crypto = cryptoByPeer.computeIfAbsent(peerId, k -> new CryptoManager());
            byte[] pub = crypto.generateKeyPair();
            socket.sendPacket(OpCode.KEY_EXCHANGE, peerId, 0, pub);
        } catch (Exception e) {
            notifySystem("Error en intercambio de claves: " + e.getMessage());
        }
    }

    /**
     * Crea un grupo y recuerda los miembros a agregar una vez el servidor
     * confirme el group_id real (ver handleAck / FIX added_to_group).
     */
    public void createGroup(String name, java.util.List<Long> memberIds) {
        pendingGroupMembers = memberIds;
        String payload = Json.obj().put("name", name).build();
        trySend(OpCode.GROUP_CREATE, 0, 0, payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Agrega un miembro a un grupo. El servidor (ClientHandler.handleGroupJoin)
     * espera {"group_id":N,"target_user_id":M} -- un ID numerico, no un username.
     * Si la UI solo tiene el username, debe resolverlo a ID antes de llamar aqui
     * (pendiente de confirmar con Persona A si existe un lookup username->id).
     */
    /**
     * Agrega un miembro a un grupo. El servidor espera {"group_id":N,"target_user_id":M}
     * (ID numerico, no username). Resolver username->id en la UI antes de llamar
     * (pendiente de confirmar con Persona A si existe un lookup).
     *
     * @param groupName se incluye en el payload (campo extra "group_name") para
     *                   que el servidor lo reenvie en la notificacion al
     *                   agregado (ver ClientHandler.handleGroupJoin / FIX
     *                   added_to_group). Sin esto, el agregado solo veia
     *                   "Grupo <id>" porque ni GroupManager ni DatabaseManager
     *                   exponen un metodo para consultar el nombre por ID.
     */
    public void addToGroup(long groupId, long targetUserId, String groupName) {
        String payload = Json.obj()
                .put("group_id", groupId)
                .put("target_user_id", targetUserId)
                .put("group_name", groupName)
                .build();
        trySend(OpCode.GROUP_JOIN, groupId, Packet.FLAG_IS_GROUP,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    public void requestQrToken() {
        String payload = Json.obj().put("device_type", deviceType).build();
        trySend(OpCode.QR_GENERATE, 0, 0, payload.getBytes(StandardCharsets.UTF_8));
    }

    public void validateQr(String token) {
        String payload = Json.obj().put("token", token).put("device_type", deviceType).build();
        trySend(OpCode.QR_VALIDATE, 0, 0, payload.getBytes(StandardCharsets.UTF_8));
    }

    private void trySend(OpCode op, long to, int flags, byte[] payload) {
        try {
            socket.sendPacket(op, to, flags, payload);
        } catch (Exception e) {
            notifySystem("No conectado: " + e.getMessage());
        }
    }

    // ----------------- Eventos del socket -----------------

    @Override
    public void onConnected() {
        edt(() -> ui.onConnectionState(true, "Conectado"));
    }

    @Override
    public void onDisconnected(String reason) {
        edt(() -> ui.onConnectionState(false, reason));
    }

    @Override
    public void onPacket(Packet p) {
        try {
            switch (p.opcode()) {
                case AUTH_RESPONSE:   handleAuthResponse(p); break;
                case MSG_TEXT:        handleIncomingText(p, false); break;
                case GROUP_MSG:       handleIncomingText(p, true); break;
                case MSG_ACK:         handleAck(p); break;
                case KEY_EXCHANGE:    handleKeyExchange(p); break;
                case QR_GENERATE:     handleQrGenerateResponse(p); break;
                case QR_VALIDATE:     handleQrValidateResponse(p); break;
                case HISTORY_SYNC:    handleHistorySync(p); break;
                case PING:            break; // keep-alive de vuelta, ignorar
                default:
                    notifySystem("Paquete no manejado: " + p.opcode());
            }
        } catch (Exception e) {
            notifySystem("Error procesando " + p.opcode() + ": " + e.getMessage());
        }
    }

    /**
     * MSG_ACK puede ser: confirmacion normal de entrega, notificacion de
     * "added_to_group" (FIX, ver arriba), o el ACK de GROUP_CREATE con el
     * group_id real ({"ok":true,"group_id":N,"name":"..."}). En este ultimo
     * caso se disparan los GROUP_JOIN pendientes que createGroup() guardo,
     * porque antes el comentario decia "se envian cuando el servidor
     * confirme el group_id" pero ese paso nunca se implementaba.
     */
    private void handleAck(Packet p) {
        Map<String, String> m = Json.decode(p.payloadAsString());
        if ("added_to_group".equals(m.get("action"))) {
            long groupId = parseLong(m.get("group_id"), -1);
            long byUserId = parseLong(m.get("by"), -1);
            String groupName = m.getOrDefault("group_name", "");
            if (groupId >= 0) edt(() -> ui.onAddedToGroup(groupId, byUserId, groupName));
            return;
        }
        boolean isGroupCreateAck = "true".equalsIgnoreCase(m.getOrDefault("ok", "false"))
                && m.containsKey("group_id") && m.containsKey("name");
        if (isGroupCreateAck) {
            long groupId = parseLong(m.get("group_id"), -1);
            String name = m.getOrDefault("name", "Grupo " + groupId);
            java.util.List<Long> members = pendingGroupMembers;
            pendingGroupMembers = null;
            if (groupId >= 0) {
                if (members != null) {
                    for (Long memberId : members) addToGroup(groupId, memberId, name);
                }
                final long gid = groupId;
                edt(() -> ui.onGroupCreated(gid, name));
            }
            return;
        }
        edt(() -> ui.onAck(p.sequence()));
    }

    private void handleAuthResponse(Packet p) {
        Map<String, String> m = Json.decode(p.payloadAsString());
        // El servidor (ClientHandler.handleAuth + AuthResult.toJson) responde con
        // "ok": true/false, igual que el resto de respuestas del broker QR.
        // ADVERTENCIA: no tenemos el codigo fuente de AuthResult.toJson(); los
        // nombres "user_id"/"token"/"username" son una suposicion razonable a
        // partir del estilo del resto del servidor. Confirmar con Persona A.
        boolean ok = "true".equalsIgnoreCase(m.getOrDefault("ok", "false"));
        if (ok) {
            long uid = parseLong(m.get("user_id"), 0);
            socket.setMyUserId(uid);
            edt(() -> ui.onAuthResult(true, uid, m.getOrDefault("token", "")));
        } else {
            edt(() -> ui.onAuthResult(false, 0, m.getOrDefault("error", "Autenticacion fallida")));
        }
    }

    private void handleIncomingText(Packet p, boolean isGroup) throws Exception {
        String text;
        if (p.isEncrypted() && !isGroup) {
            CryptoManager crypto = cryptoByPeer.get(p.senderId());
            if (crypto == null || !crypto.isReady()) {
                // aun no tenemos clave: pedir intercambio y mostrar placeholder
                startKeyExchange(p.senderId());
                text = "[mensaje cifrado: estableciendo clave...]";
            } else {
                text = new String(crypto.decrypt(p.payload()), StandardCharsets.UTF_8);
            }
        } else {
            text = p.payloadAsString();
        }

        // peer de la conversacion: en 1-a-1 es el remitente; en grupo es el receiver (group id)
        long convId = isGroup ? p.receiverId() : p.senderId();

        LocalCache.CachedMessage cm = new LocalCache.CachedMessage();
        cm.id = 0;
        cm.peerId = convId;
        cm.isGroup = isGroup;
        cm.senderId = p.senderId();
        cm.type = "text";
        cm.text = text;
        cm.timestamp = p.timestamp();
        cm.status = "delivered";
        cache.insert(cm);

        final String t = text;
        edt(() -> ui.onTextMessage(convId, isGroup, p.senderId(), t, p.timestamp()));
    }

    private void handleKeyExchange(Packet p) throws Exception {
        long peer = p.senderId();
        CryptoManager crypto = cryptoByPeer.computeIfAbsent(peer, k -> new CryptoManager());
        // Si aun no generamos nuestro par, generarlo y responder.
        boolean needReply = false;
        if (crypto.getPublicKeyEncodedOrNull() == null) {
            crypto.generateKeyPair();
            needReply = true;
        }
        crypto.deriveSharedKey(p.payload());
        if (needReply) {
            socket.sendPacket(OpCode.KEY_EXCHANGE, peer, 0, crypto.getPublicKeyEncoded());
        }
        notifySystem("Clave E2E establecida con usuario " + peer);
        flushPending(peer);
    }

    /** Respuesta a QR_GENERATE del servidor: {"token":"...","expires_in_seconds":60} */
    private void handleQrGenerateResponse(Packet p) {
        Map<String, String> m = Json.decode(p.payloadAsString());
        String token = m.get("token");
        int expires = parseIntSafe(m.get("expires_in_seconds"), 60);
        if (token != null) edt(() -> ui.onQrToken(token, expires));
    }

    /** Respuesta a QR_VALIDATE del servidor: {"ok":true/false,"message":"..."} (sin token). */
    private void handleQrValidateResponse(Packet p) {
        Map<String, String> m = Json.decode(p.payloadAsString());
        boolean ok = "true".equalsIgnoreCase(m.getOrDefault("ok", "false"));
        String message = m.getOrDefault("message", "");
        edt(() -> ui.onQrValidated(ok, message));
    }

    private static int parseIntSafe(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private void handleHistorySync(Packet p) {
        // Payload confirmado por protocol_spec.md 3.13: array JSON de metadatos
        // [{"id":1,"from":3,"to":7,"type":"text","ts":"..."}]. NOTA: solo trae
        // metadatos, NO el contenido cifrado del mensaje -- no hay (todavia) un
        // mecanismo documentado para recuperar el texto real de cada entrada.
        var entries = Json.decodeArray(p.payloadAsString());
        edt(() -> ui.onHistorySynced(entries.size()));
    }

    // ----------------- Utilidades -----------------

    private void notifySystem(String msg) {
        edt(() -> { if (ui != null) ui.onSystem(msg); });
    }

    private void edt(Runnable r) {
        if (ui == null) return;
        SwingUtilities.invokeLater(r);
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}