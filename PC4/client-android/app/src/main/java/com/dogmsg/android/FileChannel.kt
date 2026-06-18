package com.dogmsg.android

import com.dogmsg.android.protocol.Json
import com.dogmsg.android.protocol.OpCode
import com.dogmsg.android.protocol.Packet
import com.dogmsg.android.protocol.PacketParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Canal de archivos (puerto 9001): envio y recepcion de MSG_FILE/MSG_IMAGE +
 * FILE_CHUNK + FILE_COMPLETE. Equivalente Kotlin combinado de FileChunker.java
 * + FileReceiver.java del cliente desktop.
 *
 * Protocolo de transferencia (igual que el cliente Java, validado contra el
 * servidor real):
 *  1. AUTH_REQUEST (login) como PRIMER paquete de CUALQUIER conexion nueva a
 *     este puerto. ClientHandler.handleAuth lo exige tambien aqui.
 *  2. Metadata SIEMPRE como MSG_FILE (incluso para imagenes): el servidor real
 *     (ClientHandler.dispatch) solo conecta MSG_FILE a FileTransferHandler;
 *     MSG_IMAGE esta enchufado a MessageRouter.routePrivate (relay simple, NO
 *     registra transfer_id), lo que causaria que los FILE_CHUNK/FILE_COMPLETE
 *     posteriores lleguen a una "transferencia desconocida". mime_type es lo
 *     que distingue imagen de archivo, no el opcode.
 *  3. N FILE_CHUNK con payload "transferId|chunkIndex|" + bytes binarios.
 *  4. FILE_COMPLETE con {transfer_id, checksum_crc32}.
 *
 * RIESGO CONOCIDO (igual que en el cliente Java): si esta misma conexion
 * autentica con el mismo userId que el canal de mensajes, el servidor
 * (ClientHandler, fix de Persona B) la adjunta como canal secundario de
 * archivos en vez de pisar el canal de texto -- pero solo si ese fix esta
 * desplegado en el servidor que se use.
 */
class FileChannel(
    private val context: android.content.Context,
    private val host: String,
    private val filePort: Int,
    private val authPayloadSupplier: () -> ByteArray?,
    private val listener: Listener
) {

    interface Listener {
        fun onFileReceived(senderId: Long, receiverId: Long, isImage: Boolean, savedFile: File)
        fun onError(message: String)
    }

    companion object {
        const val CHUNK_SIZE = 64 * 1024          // 64 KB
        const val MAX_FILE_SIZE = 25L * 1024 * 1024 // 25 MB
    }

    private data class IncomingTransfer(
        val transferId: String,
        val filename: String,
        val mimeType: String,
        val totalChunks: Int,
        val senderId: Long,
        val receiverId: Long,
        val isImage: Boolean,
        val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    )

    private val active = ConcurrentHashMap<String, IncomingTransfer>()

    @Volatile private var running = false
    private var receiveThread: Thread? = null

    // FIX CRITICO: antes sendFile() abria una conexion NUEVA al puerto 9001
    // por cada envio, ademas de la conexion persistente de recepcion ya
    // abierta por connectAndListen(). El servidor (ClientHandler) solo
    // admite UN canal de archivos secundario por userId (existing.fileChannel
    // = this, sin acumular); al llegar la segunda conexion, pisaba la
    // referencia a la primera. Cuando sendFile() terminaba y cerraba su
    // socket, el cleanup() del servidor borraba esa referencia por completo
    // (primary.fileChannel = null), dejando a la conexion de recepcion
    // original huerfana del lado del servidor -- de ahi el error "Canal de
    // archivos desconectado" justo despues de enviar. Ahora se reutiliza el
    // MISMO socket/OutputStream que connectAndListen ya mantiene abierto.
    @Volatile private var sharedOut: OutputStream? = null
    private val writeLock = Object()

    // ----------------- Envio -----------------

    /** Envia un archivo o imagen al peer. Bloqueante: llamar desde un hilo de fondo. */
    @Throws(IOException::class)
    fun sendFile(senderId: Long, receiverId: Long, file: File, isImage: Boolean, onProgress: (Int) -> Unit) {
        val size = file.length()
        if (size > MAX_FILE_SIZE) throw IOException("Archivo excede el limite de 25 MB")

        val out = sharedOut
            ?: throw IOException("Canal de archivos no esta listo todavia. Intenta de nuevo en un momento.")

        val transferId = UUID.randomUUID().toString().replace("-", "")
        val totalChunks = Math.ceil(size.toDouble() / CHUNK_SIZE).toInt().coerceAtLeast(1)
        val mime = guessMimeType(file, isImage)

        synchronized(writeLock) {
            // Metadata SIEMPRE como MSG_FILE (ver nota de clase arriba).
            val meta = Json.obj()
                .put("transfer_id", transferId)
                .put("filename", file.name)
                .put("size", size)
                .put("mime_type", mime)
                .put("total_chunks", totalChunks)
                .build()
            val metaPkt = Packet.now(OpCode.MSG_FILE, 0, senderId, receiverId, 0,
                meta.toByteArray(StandardCharsets.UTF_8))
            out.write(metaPkt.toBytes())
            out.flush()
        }

        // Chunks: "transferId|chunkIndex|" + bytes
        val crc = CRC32()
        file.inputStream().use { fis ->
            var index = 0
            val buf = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                crc.update(buf, 0, read)

                val prefix = "$transferId|$index|".toByteArray(StandardCharsets.UTF_8)
                val chunkPayload = ByteArray(prefix.size + read)
                System.arraycopy(prefix, 0, chunkPayload, 0, prefix.size)
                System.arraycopy(buf, 0, chunkPayload, prefix.size, read)

                val chunkPkt = Packet.now(OpCode.FILE_CHUNK, 0, senderId, receiverId, 0, chunkPayload)
                synchronized(writeLock) {
                    out.write(chunkPkt.toBytes())
                    out.flush()
                }

                index++
                onProgress(((index.toDouble() / totalChunks) * 100).toInt().coerceAtMost(100))
            }
        }

        // FILE_COMPLETE con checksum real
        val complete = Json.obj()
            .put("transfer_id", transferId)
            .put("checksum_crc32", crc.value)
            .build()
        val completePkt = Packet.now(OpCode.FILE_COMPLETE, 0, senderId, receiverId, 0,
            complete.toByteArray(StandardCharsets.UTF_8))
        synchronized(writeLock) {
            out.write(completePkt.toBytes())
            out.flush()
        }
    }

    private fun guessMimeType(file: File, isImage: Boolean): String {
        if (isImage) {
            return when (file.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
        }
        return "application/octet-stream"
    }

    // ----------------- Recepcion -----------------

    /** Inicia el hilo de escucha persistente. Reintenta con espera fija si falla. */
    fun startReceiving() {
        if (running) return
        running = true
        receiveThread = Thread(::receiveLoop, "file-receiver").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        receiveThread?.interrupt()
        sharedOut = null
    }

    private fun receiveLoop() {
        while (running) {
            try {
                connectAndListen()
            } catch (e: Exception) {
                if (running) listener.onError("Canal de archivos desconectado: ${e.message}")
            }
            if (!running) return
            try { Thread.sleep(3000) } catch (ie: InterruptedException) { return }
        }
    }

    private fun connectAndListen() {
        val authPayload = authPayloadSupplier()
        if (authPayload == null) {
            Thread.sleep(1000)
            return
        }

        Socket().use { s ->
            s.connect(InetSocketAddress(host, filePort), 5000)
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val parser = PacketParser(s.getInputStream())

            authenticateOrThrow(s, out, parser)
            sharedOut = out

            try {
                while (running) {
                    val p = parser.readPacket() ?: break
                    handlePacket(p)
                }
            } finally {
                // Esta conexion ya no sirve para enviar (se va a cerrar al
                // salir del use{}); sendFile() debe fallar explicitamente
                // en vez de escribir a un socket muerto.
                sharedOut = null
            }
        }
    }

    @Throws(IOException::class)
    private fun authenticateOrThrow(s: Socket, out: OutputStream, existingParser: PacketParser? = null) {
        val authPayload = authPayloadSupplier()
            ?: throw IOException("No hay sesion activa todavia.")

        val authPkt = Packet.now(OpCode.AUTH_REQUEST, 0, 0, 0, 0, authPayload)
        out.write(authPkt.toBytes())
        out.flush()

        val parser = existingParser ?: PacketParser(s.getInputStream())
        val resp = parser.readPacket()
        if (resp == null || resp.opcode != OpCode.AUTH_RESPONSE) {
            throw IOException("El servidor no autentico el canal de archivos.")
        }
        val m = Json.decode(resp.payloadAsString())
        if (!m["ok"].equals("true", ignoreCase = true)) {
            throw IOException("Autenticacion fallida: ${m["error"] ?: "desconocido"}")
        }
    }

    private fun handlePacket(p: Packet) {
        when (p.opcode) {
            OpCode.MSG_FILE, OpCode.MSG_IMAGE -> handleMetadata(p)
            OpCode.FILE_CHUNK -> handleChunk(p)
            OpCode.FILE_COMPLETE -> handleComplete(p)
            else -> { /* PING u otros opcodes inesperados en este canal; ignorar */ }
        }
    }

    /** Metadata: siempre llega como MSG_FILE; isImage se decide por mime_type. */
    private fun handleMetadata(p: Packet) {
        val m = Json.decode(p.payloadAsString())
        val transferId = m["transfer_id"] ?: return
        val mime = m["mime_type"] ?: "application/octet-stream"
        val t = IncomingTransfer(
            transferId = transferId,
            filename = m["filename"] ?: "archivo_recibido",
            mimeType = mime,
            totalChunks = m["total_chunks"]?.toIntOrNull() ?: 0,
            senderId = p.senderId,
            receiverId = p.receiverId,
            isImage = mime.startsWith("image/")
        )
        active[transferId] = t
    }

    /** Chunk: payload = "transferId|chunkIndex|" + bytes crudos. */
    private fun handleChunk(p: Packet) {
        val raw = p.payload
        if (raw.size < 3) return

        val firstPipe = raw.indexOf('|'.code.toByte())
        if (firstPipe < 0) return
        val secondPipe = raw.indexOf('|'.code.toByte(), firstPipe + 1)
        if (secondPipe < 0) return

        val transferId = String(raw, 0, firstPipe, StandardCharsets.UTF_8)
        val idxStr = String(raw, firstPipe + 1, secondPipe - firstPipe - 1, StandardCharsets.UTF_8)
        val chunkIndex = idxStr.toIntOrNull() ?: return

        val t = active[transferId] ?: return
        val data = raw.copyOfRange(secondPipe + 1, raw.size)
        t.chunks[chunkIndex] = data
    }

    /** Fin: {transfer_id, checksum_crc32} -> ensambla y escribe a disco. */
    private fun handleComplete(p: Packet) {
        val m = Json.decode(p.payloadAsString())
        val transferId = m["transfer_id"] ?: return
        val t = active.remove(transferId) ?: return

        try {
            val outDir = File(downloadsDirOrFallback(), "DogMessenger")
            outDir.mkdirs()
            val outFile = File(outDir, "${transferId}_${t.filename}")
            FileOutputStream(outFile).use { fos ->
                for (i in 0 until t.totalChunks) {
                    t.chunks[i]?.let { fos.write(it) }
                }
            }
            listener.onFileReceived(t.senderId, t.receiverId, t.isImage, outFile)
        } catch (e: IOException) {
            listener.onError("Error guardando archivo recibido: ${e.message}")
        }
    }

    private fun downloadsDirOrFallback(): File {
        // Coincide con <external-files-path name="received_files" path="DogMessenger" />
        // declarado en res/xml/file_paths.xml, para que FileProvider pueda
        // exponer estos archivos sin depender de permisos de almacenamiento legacy.
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    private fun ByteArray.indexOf(target: Byte, from: Int = 0): Int {
        for (i in from until this.size) if (this[i] == target) return i
        return -1
    }
}