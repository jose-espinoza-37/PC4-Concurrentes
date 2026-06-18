package com.dogmsg.android

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dogmsg.android.protocol.Json
import com.dogmsg.android.protocol.OpCode
import com.dogmsg.android.protocol.Packet
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Logica central del cliente Android (T-B9). Es el espejo de ChatController.java:
 * orquesta el [SocketClient] (TCP), un [CryptoManager] por conversacion (E2E),
 * el [LocalCache] (mensajes locales) y el [FileChannel] (envio/recepcion de
 * archivos e imagenes por el puerto de archivos).
 *
 * Recibe los paquetes entrantes, los descifra/persiste y notifica a la UI en el
 * hilo principal de Android (Looper.getMainLooper()).
 */
class ChatEngine(
    private val context: android.content.Context,
    host: String,
    port: Int,
    private val filePort: Int,
    private val cache: LocalCache,
    private val deviceType: String
) : SocketClient.Listener {

    /** Hooks que implementan las Activities para recibir eventos. */
    interface UiCallbacks {
        fun onConnectionState(connected: Boolean, detail: String)
        fun onAuthResult(ok: Boolean, userId: Long, tokenOrError: String)
        fun onTextMessage(peerId: Long, isGroup: Boolean, senderId: Long, text: String, timestamp: Long)
        fun onAck(sequence: Long)
        fun onQrToken(token: String, expiresInSeconds: Int)
        fun onQrValidated(ok: Boolean, message: String)
        fun onHistorySynced(count: Int)
        fun onAddedToGroup(groupId: Long, byUserId: Long, groupName: String) // FIX notificacion GROUP_JOIN ajeno
        fun onGroupCreated(groupId: Long, name: String)                     // FIX ack real de GROUP_CREATE propio
        fun onFileReceived(senderId: Long, receiverId: Long, isImage: Boolean, savedFile: java.io.File)
        fun onSystem(message: String)
    }

    private val socket = SocketClient(host, port, this)
    private val main = Handler(Looper.getMainLooper())
    private val cryptoByPeer = ConcurrentHashMap<Long, CryptoManager>()
    // Mensajes en espera de clave compartida (la spec exige encrypted=1
    // siempre en MSG_TEXT; nunca se envia texto plano por la red).
    private val pendingByPeer = ConcurrentHashMap<Long, MutableList<String>>()
    // Miembros (IDs) pendientes de agregar al grupo recien creado; se usan
    // cuando llega el MSG_ACK de GROUP_CREATE con el group_id real.
    @Volatile private var pendingGroupMembers: List<Long>? = null

    private var fileChannel: FileChannel? = null

    @Volatile private var ui: UiCallbacks? = null

    fun setUi(ui: UiCallbacks?) { this.ui = ui }
    fun start() = socket.start()
    fun shutdown() {
        socket.shutdown()
        fileChannel?.stop()
    }
    fun myUserId(): Long = socket.myUserId
    fun isConnected(): Boolean = socket.isConnected()

    /**
     * Lista de conversaciones (1-1 y grupos) con su ultimo mensaje, leida
     * desde el cache local persistente. Usado por MainActivity para poblar
     * la lista de chats al iniciar o al volver de un ChatActivity -- antes
     * la lista solo se llenaba en memoria en vivo y se vaciaba al recrearse
     * la Activity, aunque los mensajes seguian guardados en SQLite.
     */
    fun conversationSummaries(): List<LocalCache.ConversationSummary> = cache.conversationSummaries()

    private var lastUsername: String? = null
    private var lastPasswordHash: String? = null

    /**
     * Construye el payload AUTH_REQUEST (accion "login") con las credenciales
     * de la sesion actual. Lo usa [FileChannel] para autenticar su propia
     * conexion al puerto de archivos, tal como exige ClientHandler.handleAuth
     * en CUALQUIER conexion (no solo la de mensajes).
     */
    fun buildFileChannelAuthPayload(): ByteArray? {
        val u = lastUsername ?: return null
        val p = lastPasswordHash ?: return null
        val payload = Json.obj()
            .put("username", u)
            .put("password_hash", p)
            .put("action", "login")
            .put("device_type", deviceType)
            .build()
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    // ----------------- Acciones desde la UI -----------------

    /** Envia AUTH_REQUEST (login o registro). La contrasena se hashea aqui. */
    fun authenticate(username: String, password: String, register: Boolean) {
        lastUsername = username
        lastPasswordHash = CryptoManager.hashPassword(password)
        val payload = Json.obj()
            .put("username", username)
            .put("password_hash", lastPasswordHash)
            .put("action", if (register) "register" else "login")
            .put("device_type", deviceType)
            .build()
        trySend(OpCode.AUTH_REQUEST, 0, 0, payload.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Envia un mensaje de texto cifrado E2E al peer.
     *
     * La spec (protocol_spec.md 3.3) exige flags.encrypted=1 siempre en
     * MSG_TEXT: payload = [16 bytes IV][ciphertext]. Por eso nunca se manda
     * texto plano: si aun no hay clave compartida, se encola y se dispara
     * KEY_EXCHANGE; al completarse, flushPending() envia lo acumulado.
     *
     * NOTA GROUP_MSG: la spec (3.10) tambien pide encrypted=1 en grupo, pero
     * EncryptionBroker.java del servidor solo implementa DH 1-a-1 (un par de
     * claves por par de usuarios), sin clave de grupo documentada. Hasta que
     * Persona A aclare el mecanismo, GROUP_MSG se envia sin flag encrypted,
     * que es el unico cifrado que el servidor realmente soporta hoy.
     */
    fun sendText(peerId: Long, isGroup: Boolean, text: String) {
        if (isGroup) {
            sendGroupPlain(peerId, text)
            return
        }
        if (peerId == BOT_USER_ID) {
            // El bot de ventas (sales_node.py) ignora cualquier mensaje con
            // flags & 0x01 (cifrado) -- ver reader_thread() en ese archivo.
            // Por eso, igual que con los grupos, se manda sin cifrar.
            sendPlainToUser(peerId, text)
            return
        }
        try {
            val crypto = cryptoByPeer[peerId]
            if (crypto != null && crypto.isReady()) {
                doSendEncrypted(peerId, text, crypto)
            } else {
                pendingByPeer.getOrPut(peerId) { mutableListOf() }.add(text)
                cacheAsSent(peerId, false, text, 0)
                startKeyExchange(peerId)
            }
        } catch (e: Exception) {
            notifySystem("Error al enviar: ${e.message}")
        }
    }

    private fun sendPlainToUser(peerId: Long, text: String) {
        try {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            val seq = socket.sendPacket(OpCode.MSG_TEXT, peerId, 0, payload)
            cacheAsSent(peerId, false, text, seq)
        } catch (e: Exception) {
            notifySystem("Error al enviar: ${e.message}")
        }
    }

    private fun sendGroupPlain(groupId: Long, text: String) {
        try {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            val seq = socket.sendPacket(OpCode.GROUP_MSG, groupId, Packet.FLAG_IS_GROUP, payload)
            cacheAsSent(groupId, true, text, seq)
        } catch (e: Exception) {
            notifySystem("Error al enviar al grupo: ${e.message}")
        }
    }

    private fun doSendEncrypted(peerId: Long, text: String, crypto: CryptoManager) {
        val payload = crypto.encrypt(text.toByteArray(StandardCharsets.UTF_8))
        val seq = socket.sendPacket(OpCode.MSG_TEXT, peerId, Packet.FLAG_ENCRYPTED, payload)
        cacheAsSent(peerId, false, text, seq)
    }

    private fun cacheAsSent(peerId: Long, isGroup: Boolean, text: String, seq: Long) {
        cache.insert(
            LocalCache.CachedMessage(
                id = seq,
                peerId = peerId,
                isGroup = isGroup,
                senderId = myUserId(),
                type = "text",
                text = text,
                timestamp = System.currentTimeMillis(),
                status = "sent"
            )
        )
    }

    /** Envia todos los mensajes que quedaron en espera de clave para este peer. */
    private fun flushPending(peerId: Long) {
        val queued = pendingByPeer.remove(peerId) ?: return
        val crypto = cryptoByPeer[peerId]
        if (crypto == null || !crypto.isReady()) return
        for (text in queued) {
            try {
                doSendEncrypted(peerId, text, crypto)
            } catch (e: Exception) {
                notifySystem("Error al enviar mensaje en espera: ${e.message}")
            }
        }
    }

    /** Inicia el intercambio de claves DH con un peer (T-B7). */
    fun startKeyExchange(peerId: Long) {
        try {
            val crypto = cryptoByPeer.getOrPut(peerId) { CryptoManager() }
            val pub = crypto.generateKeyPair()
            socket.sendPacket(OpCode.KEY_EXCHANGE, peerId, 0, pub)
        } catch (e: Exception) {
            notifySystem("Error en intercambio de claves: ${e.message}")
        }
    }

    /**
     * Crea un grupo y recuerda los miembros a agregar una vez el servidor
     * confirme el group_id real (ver handleAck / FIX added_to_group).
     */
    fun createGroup(name: String, memberIds: List<Long>) {
        pendingGroupMembers = memberIds
        val payload = Json.obj().put("name", name).build()
        trySend(OpCode.GROUP_CREATE, 0, 0, payload.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Agrega un miembro a un grupo. El servidor espera {"group_id":N,"target_user_id":M}
     * (ID numerico, no username; no existe lookup username->id).
     *
     * @param groupName se incluye en el payload (campo extra "group_name") para
     *                   que el servidor lo reenvie en la notificacion al
     *                   agregado (FIX added_to_group). Sin esto, el agregado
     *                   solo veia "Grupo <id>" porque ni GroupManager ni
     *                   DatabaseManager exponen consulta de nombre por ID.
     */
    fun addToGroup(groupId: Long, targetUserId: Long, groupName: String) {
        val payload = Json.obj()
            .put("group_id", groupId)
            .put("target_user_id", targetUserId)
            .put("group_name", groupName)
            .build()
        trySend(OpCode.GROUP_JOIN, groupId, Packet.FLAG_IS_GROUP, payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun requestQrToken() {
        val payload = Json.obj().put("device_type", deviceType).build()
        trySend(OpCode.QR_GENERATE, 0, 0, payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun validateQr(token: String) {
        val payload = Json.obj().put("token", token).put("device_type", deviceType).build()
        trySend(OpCode.QR_VALIDATE, 0, 0, payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun recentMessages(peerId: Long, isGroup: Boolean, limit: Int) =
        cache.recent(peerId, isGroup, limit)

    /** Envia un archivo o imagen al peer por el canal de archivos (puerto 9001). */
    fun sendFile(receiverId: Long, file: java.io.File, isImage: Boolean, onProgress: (Int) -> Unit) {
        ensureFileChannel()
        fileChannel?.sendFile(myUserId(), receiverId, file, isImage, onProgress)
            ?: notifySystem("Canal de archivos no disponible todavia.")
    }

    /** Arranca el receptor de archivos (llamar tras login exitoso). */
    fun startFileChannel() {
        ensureFileChannel()
        fileChannel?.startReceiving()
    }

    private fun ensureFileChannel() {
        if (fileChannel == null) {
            fileChannel = FileChannel(
                context = context,
                host = socket.host,
                filePort = filePort,
                authPayloadSupplier = { buildFileChannelAuthPayload() },
                listener = object : FileChannel.Listener {
                    override fun onFileReceived(senderId: Long, receiverId: Long, isImage: Boolean, savedFile: java.io.File) {
                        post { ui?.onFileReceived(senderId, receiverId, isImage, savedFile) }
                    }
                    override fun onError(message: String) {
                        notifySystem(message)
                    }
                }
            )
        }
    }

    private fun trySend(op: OpCode, to: Long, flags: Int, payload: ByteArray) {
        try {
            Log.d(TAG, "Enviando $op a $to (conectado=${socket.isConnected()})")
            socket.sendPacket(op, to, flags, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo enviando $op: ${e.javaClass.simpleName}: ${e.message}", e)
            notifySystem("No conectado: ${e.message}")
        }
    }

    // ----------------- Eventos del socket -----------------

    override fun onConnected() = post { ui?.onConnectionState(true, "Conectado") }

    override fun onDisconnected(reason: String) = post { ui?.onConnectionState(false, reason) }

    override fun onPacket(packet: Packet) {
        try {
            when (packet.opcode) {
                OpCode.AUTH_RESPONSE -> handleAuthResponse(packet)
                OpCode.MSG_TEXT -> handleIncomingText(packet, false)
                OpCode.GROUP_MSG -> handleIncomingText(packet, true)
                OpCode.MSG_ACK -> handleAck(packet)
                OpCode.KEY_EXCHANGE -> handleKeyExchange(packet)
                OpCode.QR_GENERATE -> handleQrGenerateResponse(packet)
                OpCode.QR_VALIDATE -> handleQrValidateResponse(packet)
                OpCode.HISTORY_SYNC -> handleHistorySync(packet)
                OpCode.PING -> { /* keep-alive de vuelta, ignorar */ }
                else -> notifySystem("Paquete no manejado: ${packet.opcode}")
            }
        } catch (e: Exception) {
            notifySystem("Error procesando ${packet.opcode}: ${e.message}")
        }
    }

    private fun handleAuthResponse(p: Packet) {
        val raw = p.payloadAsString()
        Log.d(TAG, "AUTH_RESPONSE crudo: $raw")
        val m = Json.decode(raw)
        // El servidor (ClientHandler.handleAuth + AuthResult.toJson) responde con
        // "ok": true/false, "token", "user_id", "username" -- confirmado contra
        // el codigo real de AuthManager.AuthResult.toJson().
        val ok = m["ok"].equals("true", ignoreCase = true)
        if (ok) {
            val uid = parseLong(m["user_id"], 0)
            socket.setMyUserId(uid)
            post { ui?.onAuthResult(true, uid, m["token"] ?: "") }
        } else {
            post { ui?.onAuthResult(false, 0, m["error"] ?: "Autenticacion fallida") }
        }
    }

    /**
     * MSG_ACK puede ser: confirmacion normal de entrega, notificacion de
     * "added_to_group" (FIX), o el ACK de GROUP_CREATE con el group_id real
     * ({"ok":true,"group_id":N,"name":"..."}). En este ultimo caso se
     * disparan los GROUP_JOIN pendientes que createGroup() guardo.
     */
    private fun handleAck(p: Packet) {
        val m = Json.decode(p.payloadAsString())
        if (m["action"] == "added_to_group") {
            val groupId = parseLong(m["group_id"], -1)
            val byUserId = parseLong(m["by"], -1)
            val groupName = m["group_name"] ?: ""
            if (groupId >= 0) post { ui?.onAddedToGroup(groupId, byUserId, groupName) }
            return
        }
        val isGroupCreateAck = m["ok"].equals("true", ignoreCase = true)
                && m.containsKey("group_id") && m.containsKey("name")
        if (isGroupCreateAck) {
            val groupId = parseLong(m["group_id"], -1)
            val name = m["name"] ?: "Grupo $groupId"
            val members = pendingGroupMembers
            pendingGroupMembers = null
            if (groupId >= 0) {
                members?.forEach { addToGroup(groupId, it, name) }
                post { ui?.onGroupCreated(groupId, name) }
            }
            return
        }
        post { ui?.onAck(p.sequence) }
    }

    private fun handleIncomingText(p: Packet, isGroup: Boolean) {
        val text: String
        if (p.isEncrypted && !isGroup) {
            val crypto = cryptoByPeer[p.senderId]
            text = if (crypto == null || !crypto.isReady()) {
                startKeyExchange(p.senderId)
                "[mensaje cifrado: estableciendo clave...]"
            } else {
                String(crypto.decrypt(p.payload), StandardCharsets.UTF_8)
            }
        } else {
            text = p.payloadAsString()
        }

        // 1-a-1: la conversacion es el remitente; grupo: el receiver (group id)
        val convId = if (isGroup) p.receiverId else p.senderId

        cache.insert(
            LocalCache.CachedMessage(
                id = 0,
                peerId = convId,
                isGroup = isGroup,
                senderId = p.senderId,
                type = "text",
                text = text,
                timestamp = p.timestamp,
                status = "delivered"
            )
        )

        post { ui?.onTextMessage(convId, isGroup, p.senderId, text, p.timestamp) }
    }

    private fun handleKeyExchange(p: Packet) {
        val peer = p.senderId
        val crypto = cryptoByPeer.getOrPut(peer) { CryptoManager() }
        var needReply = false
        if (crypto.getPublicKeyEncodedOrNull() == null) {
            crypto.generateKeyPair()
            needReply = true
        }
        crypto.deriveSharedKey(p.payload)
        if (needReply) {
            socket.sendPacket(OpCode.KEY_EXCHANGE, peer, 0, crypto.getPublicKeyEncoded())
        }
        notifySystem("Clave E2E establecida con usuario $peer")
        flushPending(peer)
    }

    /** Respuesta a QR_GENERATE: {"token":"...","expires_in_seconds":60} */
    private fun handleQrGenerateResponse(p: Packet) {
        val m = Json.decode(p.payloadAsString())
        val token = m["token"]
        val expires = m["expires_in_seconds"]?.trim()?.toIntOrNull() ?: 60
        if (token != null) post { ui?.onQrToken(token, expires) }
    }

    /** Respuesta a QR_VALIDATE: {"ok":true/false,"message":"..."} (sin token). */
    private fun handleQrValidateResponse(p: Packet) {
        val m = Json.decode(p.payloadAsString())
        val ok = m["ok"].equals("true", ignoreCase = true)
        val message = m["message"] ?: ""
        post { ui?.onQrValidated(ok, message) }
    }

    private fun handleHistorySync(p: Packet) {
        // Payload confirmado por protocol_spec.md 3.13: array JSON de metadatos.
        // Solo trae metadatos, NO el contenido cifrado de cada mensaje.
        val entries = Json.decodeArray(p.payloadAsString())
        post { ui?.onHistorySynced(entries.size) }
    }

    // ----------------- Utilidades -----------------

    private fun notifySystem(msg: String) = post { ui?.onSystem(msg) }

    private fun post(r: () -> Unit) {
        if (ui == null) return
        main.post(r)
    }

    private fun parseLong(s: String?, def: Long): Long =
        try { s?.trim()?.toLong() ?: def } catch (e: NumberFormatException) { def }

    companion object {
        private const val TAG = "ChatEngine"

        /**
         * user_id asignado por el servidor al bot de ventas (ventas_bot,
         * ver sales_node.py). El bot ignora MSG_TEXT con flag encriptado
         * (flags & 0x01), por eso los mensajes hacia este peer se mandan
         * siempre en texto plano -- igual que los mensajes de grupo.
         */
        const val BOT_USER_ID = 5L
    }
}