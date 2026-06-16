package dogmsg;

import dogmsg.protocol.OpCode;
import dogmsg.protocol.Packet;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * FileTransferHandler.java
 * Dog Messenger — Transferencia de archivos e imágenes en chunks de 64 KB.
 *
 * Protocolo de transferencia (sección 7.1 — T-A7 del plan):
 *
 *  EMISOR → SERVIDOR:
 *    1. MSG_FILE/MSG_IMAGE con metadata JSON:
 *       {"filename":"...","size":12345,"mime_type":"...","total_chunks":N,"transfer_id":"..."}
 *    2. N paquetes FILE_CHUNK: payload = [4 bytes chunk_index BE][64KB datos]
 *    3. FILE_COMPLETE: payload JSON {"transfer_id":"...","checksum_crc32":NNNN}
 *
 *  SERVIDOR → DESTINATARIO:
 *    Retransmite exactamente los mismos paquetes en tiempo real.
 *    Si el destinatario está offline, los encola.
 *
 * Almacenamiento temporal: carpeta "uploads/" en el directorio de trabajo.
 * Después de retransmitir FILE_COMPLETE, los datos temporales se limpian.
 *
 * Límite de tamaño: 25 MB por archivo (sección 1 del plan).
 */
public class FileTransferHandler {

    private static final Logger log = Logger.getLogger(FileTransferHandler.class.getName());

    public  static final int    CHUNK_SIZE      = 64 * 1024;  // 64 KB
    private static final long   MAX_FILE_BYTES  = 25L * 1024 * 1024; // 25 MB
    private static final String UPLOAD_DIR      = "uploads";

    /** Estado de una transferencia en progreso. */
    private static class Transfer {
        final String  transferId;
        final int     senderId;
        final int     receiverId;
        final String  filename;
        final String  mimeType;
        final long    totalSize;
        final int     totalChunks;
        final boolean isGroup;
        // Chunks recibidos: chunkIndex → ruta temporal del chunk
        final Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
        final long startedAt = System.currentTimeMillis();

        Transfer(String transferId, int senderId, int receiverId,
                 String filename, String mimeType, long totalSize,
                 int totalChunks, boolean isGroup) {
            this.transferId  = transferId;
            this.senderId    = senderId;
            this.receiverId  = receiverId;
            this.filename    = filename;
            this.mimeType    = mimeType;
            this.totalSize   = totalSize;
            this.totalChunks = totalChunks;
            this.isGroup     = isGroup;
        }

        boolean isComplete() { return chunks.size() == totalChunks; }
    }

    private final DatabaseManager           db;
    private final Map<Integer, ClientHandler> onlineMap;
    private final OfflineQueue              offline;
    private final GroupManager              groupManager;

    /** Transferencias activas: transferId → Transfer */
    private final Map<String, Transfer> activeTransfers = new ConcurrentHashMap<>();

    public FileTransferHandler(DatabaseManager db,
                               Map<Integer, ClientHandler> onlineMap,
                               OfflineQueue offline,
                               GroupManager groupManager) {
        this.db           = db;
        this.onlineMap    = onlineMap;
        this.offline      = offline;
        this.groupManager = groupManager;

        // Crear directorio de uploads
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            log.warning("[FileTransfer] No se pudo crear directorio uploads: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Metadata inicial (MSG_FILE / MSG_IMAGE)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Registra el inicio de una transferencia a partir del paquete de metadata.
     *
     * Payload JSON esperado:
     *   {"filename":"doc.pdf","size":102400,"mime_type":"application/pdf",
     *    "total_chunks":2,"transfer_id":"abc123"}
     *
     * @param packet paquete MSG_FILE o MSG_IMAGE
     * @return true si la transferencia fue registrada correctamente
     */
    public boolean handleMetadata(Packet packet) {
        String json = packet.getPayloadAsString();
        try {
            String transferId  = extractJson(json, "transfer_id");
            String filename    = extractJson(json, "filename");
            String mimeType    = extractJson(json, "mime_type");
            long   size        = Long.parseLong(extractJson(json, "size"));
            int    totalChunks = Integer.parseInt(extractJson(json, "total_chunks"));

            if (size > MAX_FILE_BYTES) {
                log.warning("[FileTransfer] Archivo demasiado grande: " + size + " bytes");
                return false;
            }

            boolean isGroup = packet.isGroup();
            Transfer t = new Transfer(
                    transferId, packet.senderId, packet.receiverId,
                    filename, mimeType, size, totalChunks, isGroup);
            activeTransfers.put(transferId, t);

            log.info("[FileTransfer] Nueva transferencia: " + transferId
                    + " file=" + filename + " chunks=" + totalChunks
                    + " size=" + size + " B");

            // Retransmitir metadata al destinatario
            relayToDestination(packet, packet.senderId);
            return true;

        } catch (Exception e) {
            log.warning("[FileTransfer] Error parseando metadata: " + e.getMessage()
                    + " json=" + json);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Chunk (FILE_CHUNK)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Procesa un chunk de archivo recibido.
     *
     * Payload: [4 bytes chunk_index BE][4 bytes transfer_id_len BE]
     *          [transfer_id bytes][chunk datos]
     *
     * Simplificación adoptada: los primeros 4 bytes son el chunk_index,
     * los siguientes bytes son el transfer_id (string terminado en '|'),
     * el resto es el contenido del chunk.
     *
     * Formato adoptado (más simple para interop):
     *   transfer_id|chunk_index|datos_base64
     *   → El payload es UTF-8: "transferId|chunkIndex|" + datos binarios en Base64.
     */
    public void handleChunk(Packet packet) {
        byte[] rawPayload = packet.payload;
        if (rawPayload == null || rawPayload.length < 4) return;

        // Extraer transferId + chunkIndex del payload
        // Formato: transferId (hasta '|') | chunkIndex decimal (hasta '|') | datos
        String header = "";
        int dataStart = 0;
        int pipes = 0;
        for (int i = 0; i < rawPayload.length && pipes < 2; i++) {
            if (rawPayload[i] == '|') {
                pipes++;
                if (pipes == 2) { dataStart = i + 1; break; }
            }
            if (pipes < 2) header += (char) rawPayload[i];
        }

        String[] parts = header.split("\\|");
        if (parts.length < 2) {
            log.warning("[FileTransfer] Chunk con formato inválido");
            return;
        }

        String transferId = parts[0];
        int    chunkIndex;
        try {
            chunkIndex = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            log.warning("[FileTransfer] chunkIndex inválido: " + parts[1]);
            return;
        }

        Transfer t = activeTransfers.get(transferId);
        if (t == null) {
            log.warning("[FileTransfer] Chunk para transferencia desconocida: " + transferId);
            return;
        }

        // Guardar datos del chunk
        byte[] chunkData = Arrays.copyOfRange(rawPayload, dataStart, rawPayload.length);
        t.chunks.put(chunkIndex, chunkData);

        // Relay inmediato al destinatario
        relayToDestination(packet, packet.senderId);

        log.fine("[FileTransfer] Chunk " + chunkIndex + "/" + (t.totalChunks - 1)
                + " de " + transferId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Completitud (FILE_COMPLETE)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Finaliza una transferencia: verifica integridad, persiste metadatos en BD,
     * retransmite FILE_COMPLETE al destinatario y limpia estado temporal.
     *
     * Payload JSON: {"transfer_id":"...","checksum_crc32":NNNN}
     */
    public void handleComplete(Packet packet) {
        String json = packet.getPayloadAsString();
        try {
            String transferId   = extractJson(json, "transfer_id");
            Transfer t          = activeTransfers.remove(transferId);

            if (t == null) {
                log.warning("[FileTransfer] FILE_COMPLETE para transferencia desconocida: "
                        + transferId);
                return;
            }

            // Verificar CRC32 si se proporcionó
            String crcStr = extractJsonOpt(json, "checksum_crc32");
            if (!crcStr.isEmpty()) {
                long expectedCrc = Long.parseLong(crcStr);
                long actualCrc   = computeCrc32(t);
                if (actualCrc != expectedCrc) {
                    log.warning("[FileTransfer] CRC32 mismatch en " + transferId
                            + " expected=" + expectedCrc + " actual=" + actualCrc);
                } else {
                    log.info("[FileTransfer] CRC32 OK para " + transferId);
                }
            }

            // Persistir metadatos en BD
            long msgId = db.saveMessage(t.senderId, t.receiverId, t.isGroup,
                    "file", (t.filename + "|" + t.mimeType).getBytes());
            if (msgId > 0) {
                String storagePath = UPLOAD_DIR + "/" + transferId + "_" + t.filename;
                db.saveFileMetadata(msgId, t.filename, t.mimeType, t.totalSize, storagePath);
            }

            // Relay FILE_COMPLETE al destinatario
            relayToDestination(packet, packet.senderId);

            long elapsed = System.currentTimeMillis() - t.startedAt;
            log.info("[FileTransfer] Completado: " + t.filename
                    + " (" + t.totalChunks + " chunks, " + elapsed + " ms)");

        } catch (Exception e) {
            log.severe("[FileTransfer] Error en FILE_COMPLETE: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Relay interno
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Envía el paquete al destinatario (individual o grupo).
     * Si está offline, encola. Si es grupo, hace broadcast.
     */
    private void relayToDestination(Packet packet, int senderId) {
        int receiverId = packet.receiverId;

        if (packet.isGroup()) {
            // Broadcast a miembros del grupo
            groupManager.broadcastToGroup(receiverId, packet, senderId, onlineMap, offline);
        } else {
            ClientHandler handler = onlineMap.get(receiverId);
            if (handler != null) {
                handler.sendPacket(packet);
            } else {
                offline.enqueueForUser(receiverId, packet);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Calcula CRC32 del contenido ensamblado de la transferencia. */
    private long computeCrc32(Transfer t) {
        CRC32 crc = new CRC32();
        for (int i = 0; i < t.totalChunks; i++) {
            byte[] chunk = t.chunks.get(i);
            if (chunk != null) crc.update(chunk);
        }
        return crc.getValue();
    }

    /** Extrae un valor de un JSON simple (sin escapados complejos). */
    private String extractJson(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) throw new IllegalArgumentException("Clave no encontrada: " + key);
        int start = idx + search.length();
        boolean isString = json.charAt(start) == '"';
        if (isString) {
            start++;
            int end = json.indexOf('"', start);
            return json.substring(start, end);
        } else {
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            return json.substring(start, end).trim();
        }
    }

    private String extractJsonOpt(String json, String key) {
        try { return extractJson(json, key); }
        catch (Exception e) { return ""; }
    }
}
