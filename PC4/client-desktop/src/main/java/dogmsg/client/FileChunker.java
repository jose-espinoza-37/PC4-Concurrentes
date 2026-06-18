package dogmsg.client;

import dogmsg.client.protocol.Json;
import dogmsg.client.protocol.OpCode;
import dogmsg.client.protocol.Packet;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.IntConsumer;

/**
 * T-B6: Fragmentacion de archivos en chunks de 64 KB y envio por el canal de
 * archivos dedicado (puerto 9001), usando SOLO sockets nativos.
 *
 * <p>Protocolo de transferencia (acordado con Persona A / Persona C):</p>
 * <ol>
 *   <li>AUTH_REQUEST (login) como PRIMER paquete de esta conexion. ClientHandler.java
 *       linea 99 ({@code if (!handleAuth()) return;}) exige autenticacion en
 *       CUALQUIER conexion, incluida la del puerto de archivos -- Server.java lo
 *       describe como "mismo handler, puerto separado". Sin esto, el servidor
 *       rechazaba MSG_FILE/MSG_IMAGE silenciosamente (userId=-1, sin sesion).</li>
 *   <li>MSG_FILE/MSG_IMAGE con payload JSON de metadata:
 *       {@code {transfer_id, filename, size, mime_type, total_chunks}}.</li>
 *   <li>N paquetes FILE_CHUNK con payload {@code "transferId|chunkIndex|"} +
 *       bytes binarios (hasta 64 KB cada uno).</li>
 *   <li>FILE_COMPLETE con {@code {transfer_id, checksum_crc32}}.</li>
 * </ol>
 *
 * <p><b>RIESGO CONOCIDO, no resuelto del lado cliente:</b> ClientHandler.java
 * linea 107 hace {@code onlineMap.put(userId, this)} al autenticar CUALQUIER
 * conexion. Como esta clase abre una conexion nueva y se autentica con el
 * mismo userId que el canal de mensajes (puerto 9000), el servidor sobrescribe
 * en el mapa el ClientHandler de mensajes por el de archivos, dejando el canal
 * de texto "huerfano" mientras dura/despues de la transferencia. Esto es un
 * defecto de diseño del servidor (un mismo userId no deberia poder tener dos
 * ClientHandler simultaneos sin coordinarse) que requiere que Persona A lo
 * resuelva server-side; no hay parche cliente-side seguro para esto.</p>
 *
 * <p>Limite de 25 MB por archivo (decision funcional de la guia).</p>
 */
public class FileChunker {

    public static final int CHUNK_SIZE = 64 * 1024;       // 64 KB
    public static final long MAX_FILE_SIZE = 25L * 1024 * 1024; // 25 MB

    private final String host;
    private final int filePort;
    private final java.util.function.Supplier<byte[]> authPayloadSupplier;

    /**
     * @param host                host del servidor
     * @param filePort            puerto de archivos (9001)
     * @param authPayloadSupplier provee el payload AUTH_REQUEST (login) para
     *                            autenticar esta conexion antes de cada envio;
     *                            ver {@link ChatController#buildFileChannelAuthPayload()}.
     *                            Si devuelve null, se lanza excepcion (no hay
     *                            sesion activa todavia).
     */
    public FileChunker(String host, int filePort, java.util.function.Supplier<byte[]> authPayloadSupplier) {
        this.host = host;
        this.filePort = filePort;
        this.authPayloadSupplier = authPayloadSupplier;
    }

    /**
     * Envia un archivo o una imagen al destinatario abriendo una conexion al
     * puerto de archivos. Reporta el progreso 0..100 via {@code onProgress}.
     *
     * Formato real del servidor (FileTransferHandler.java / protocol_spec.md 3.4-3.5):
     *  - MSG_IMAGE y MSG_FILE comparten exactamente el mismo protocolo de
     *    metadata + FILE_CHUNK + FILE_COMPLETE; solo difieren en el opcode
     *    inicial y en que mime_type identifica una imagen.
     *  - MSG_FILE/MSG_IMAGE metadata DEBE incluir "transfer_id" (sin el, el
     *    servidor lanza excepcion al parsear y la transferencia no se registra).
     *  - FILE_CHUNK payload = "transferId|chunkIndex|" + bytes crudos del chunk
     *    (NO van en el campo sequence del paquete; van dentro del payload).
     *  - FILE_COMPLETE payload = {"transfer_id":"...","checksum_crc32":N}
     *    (el servidor verifica el CRC32 si se proporciona).
     *
     * @param senderId   id propio
     * @param receiverId destinatario (usuario o grupo)
     * @param file       archivo a enviar
     * @param isImage    true para enviar con OpCode.MSG_IMAGE, false para MSG_FILE
     * @param onProgress callback de progreso (porcentaje), puede ser null
     */
    public void sendFile(long senderId, long receiverId, File file, boolean isImage,
                          IntConsumer onProgress) throws Exception {
        long size = file.length();
        if (size > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Archivo excede el limite de 25 MB");
        }
        int totalChunks = (int) Math.ceil((double) size / CHUNK_SIZE);
        String mime = probeMime(file);
        String transferId = java.util.UUID.randomUUID().toString().replace("-", "");
        // IMPORTANTE: el servidor real (ClientHandler.dispatch) solo conecta
        // MSG_FILE a FileTransferHandler.handleMetadata (que registra el
        // transfer_id). MSG_IMAGE esta enchufado a MessageRouter.routePrivate
        // (relay de mensaje normal, NO registra transfer_id). Si se manda la
        // metadata de imagen como MSG_IMAGE, los FILE_CHUNK/FILE_COMPLETE que
        // siguen llegan a una "transferencia desconocida" y se descartan.
        // Por eso SIEMPRE se usa MSG_FILE para la metadata, sea imagen o no;
        // mime_type es lo que distingue el contenido (igual que dice la spec
        // 3.5, aunque el codigo real del servidor no enchufo MSG_IMAGE al
        // mismo flujo). isImage solo afecta como el cliente RECEPTOR la
        // muestra (FileReceiver.handleMetadata usa isImage de su propio
        // parametro, no del opcode de red).
        OpCode metaOp = OpCode.MSG_FILE;

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, filePort), 5000);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();

            // 0) autenticar esta conexion (ClientHandler.handleAuth exige
            //    AUTH_REQUEST como primer paquete en CUALQUIER conexion).
            byte[] authPayload = authPayloadSupplier.get();
            if (authPayload == null) {
                throw new IllegalStateException(
                        "No hay sesion activa todavia; inicia sesion antes de enviar archivos.");
            }
            Packet authPkt = Packet.now(OpCode.AUTH_REQUEST, 0, senderId, 0, 0, authPayload);
            out.write(authPkt.toBytes());
            out.flush();

            dogmsg.client.protocol.PacketParser authParser =
                    new dogmsg.client.protocol.PacketParser(s.getInputStream());
            Packet authResp = authParser.readPacket();
            if (authResp == null || authResp.opcode() != OpCode.AUTH_RESPONSE) {
                throw new IllegalStateException("El servidor no autentico el canal de archivos.");
            }
            var authMap = Json.decode(authResp.payloadAsString());
            if (!"true".equalsIgnoreCase(authMap.getOrDefault("ok", "false"))) {
                throw new IllegalStateException(
                        "Autenticacion del canal de archivos fallida: "
                        + authMap.getOrDefault("error", "desconocido"));
            }

            // 1) metadata (incluye transfer_id, obligatorio para el servidor)
            String meta = Json.obj()
                    .put("transfer_id", transferId)
                    .put("filename", file.getName())
                    .put("size", size)
                    .put("mime_type", mime)
                    .put("total_chunks", totalChunks)
                    .build();
            Packet metaPkt = Packet.now(metaOp, 0, senderId, receiverId,
                    0, meta.getBytes(StandardCharsets.UTF_8));
            out.write(metaPkt.toBytes());
            out.flush();

            // 2) chunks: payload = "transferId|chunkIndex|" + bytes crudos
            byte[] buf = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            java.util.zip.CRC32 fullCrc = new java.util.zip.CRC32();
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = in.read(buf)) != -1) {
                    byte[] chunkData = (read == buf.length) ? buf.clone()
                            : java.util.Arrays.copyOf(buf, read);
                    fullCrc.update(chunkData);

                    byte[] prefix = (transferId + "|" + chunkIndex + "|")
                            .getBytes(StandardCharsets.UTF_8);
                    byte[] payload = new byte[prefix.length + chunkData.length];
                    System.arraycopy(prefix, 0, payload, 0, prefix.length);
                    System.arraycopy(chunkData, 0, payload, prefix.length, chunkData.length);

                    Packet chunk = Packet.now(OpCode.FILE_CHUNK, chunkIndex, senderId,
                            receiverId, 0, payload);
                    out.write(chunk.toBytes());
                    chunkIndex++;
                    if (onProgress != null && totalChunks > 0) {
                        onProgress.accept((int) (100L * chunkIndex / totalChunks));
                    }
                }
                out.flush();
            }

            // 3) fin: transfer_id + checksum_crc32 del archivo completo
            String completeJson = Json.obj()
                    .put("transfer_id", transferId)
                    .put("checksum_crc32", fullCrc.getValue())
                    .build();
            Packet done = Packet.now(OpCode.FILE_COMPLETE, totalChunks, senderId,
                    receiverId, 0, completeJson.getBytes(StandardCharsets.UTF_8));
            out.write(done.toBytes());
            out.flush();
            if (onProgress != null) onProgress.accept(100);
        }
    }

    /** Sobrecarga de compatibilidad: envia como MSG_FILE (no imagen). */
    public void sendFile(long senderId, long receiverId, File file, IntConsumer onProgress) throws Exception {
        sendFile(senderId, receiverId, file, false, onProgress);
    }

    private static String probeMime(File f) {
        try {
            String t = Files.probeContentType(f.toPath());
            return t != null ? t : "application/octet-stream";
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }
}