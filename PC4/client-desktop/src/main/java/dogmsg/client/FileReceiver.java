package dogmsg.client;

import dogmsg.client.protocol.Json;
import dogmsg.client.protocol.OpCode;
import dogmsg.client.protocol.Packet;
import dogmsg.client.protocol.PacketParser;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Receptor del canal de archivos (puerto 9001): conexion saliente persistente,
 * autenticada igual que {@link FileChunker}, que queda escuchando indefinidamente
 * los paquetes MSG_FILE/MSG_IMAGE/FILE_CHUNK/FILE_COMPLETE que el servidor
 * reenvia hacia este usuario (ClientHandler los redirige al "canal de archivos"
 * adjunto). Sin esto, los archivos/imagenes se enviaban pero nunca se recibian.
 */
public class FileReceiver {

    public interface Listener {
        /** Se dispara cuando un archivo/imagen termino de reconstruirse en disco. */
        void onFileReceived(long senderId, long receiverId, boolean isImage, File savedFile);
        void onError(String message);
    }

    private final String host;
    private final int filePort;
    private final Supplier<byte[]> authPayloadSupplier;
    private final Listener listener;

    private volatile boolean running = false;
    private Thread thread;

    private static class IncomingTransfer {
        String transferId;
        String filename;
        String mimeType;
        int totalChunks;
        long senderId;
        long receiverId;
        boolean isImage;
        final Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
    }

    private final Map<String, IncomingTransfer> active = new ConcurrentHashMap<>();

    public FileReceiver(String host, int filePort, Supplier<byte[]> authPayloadSupplier, Listener listener) {
        this.host = host;
        this.filePort = filePort;
        this.authPayloadSupplier = authPayloadSupplier;
        this.listener = listener;
    }

    /** Inicia el hilo de escucha. Reintenta conectar con espera fija si falla. */
    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "file-receiver");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                connectAndListen();
            } catch (Exception e) {
                if (running) listener.onError("Canal de archivos desconectado: " + e.getMessage());
            }
            if (!running) return;
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
        }
    }

    private void connectAndListen() throws Exception {
        byte[] authPayload = authPayloadSupplier.get();
        if (authPayload == null) {
            // Aun no hay sesion; esperar y reintentar.
            Thread.sleep(1000);
            return;
        }

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, filePort), 5000);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            PacketParser parser = new PacketParser(in);

            // Autenticar esta conexion (igual que FileChunker / ver ClientHandler.handleAuth).
            Packet authPkt = Packet.now(OpCode.AUTH_REQUEST, 0, 0, 0, 0, authPayload);
            out.write(authPkt.toBytes());
            out.flush();

            Packet authResp = parser.readPacket();
            if (authResp == null || authResp.opcode() != OpCode.AUTH_RESPONSE) {
                throw new IOException("El servidor no autentico el canal de archivos.");
            }
            Map<String, String> authMap = Json.decode(authResp.payloadAsString());
            if (!"true".equalsIgnoreCase(authMap.getOrDefault("ok", "false"))) {
                throw new IOException("Autenticacion fallida: " + authMap.getOrDefault("error", "?"));
            }

            // Escuchar indefinidamente
            while (running) {
                Packet p = parser.readPacket();
                if (p == null) break; // servidor cerro la conexion
                handlePacket(p);
            }
        }
    }

    private void handlePacket(Packet p) {
        switch (p.opcode()) {
            case MSG_FILE:
            case MSG_IMAGE:
                handleMetadata(p);
                break;
            case FILE_CHUNK:
                handleChunk(p);
                break;
            case FILE_COMPLETE:
                handleComplete(p);
                break;
            default:
                // PING u otros opcodes inesperados en este canal; ignorar.
        }
    }

    /** Metadata: {"transfer_id","filename","size","mime_type","total_chunks"}
     *  NOTA: ahora siempre llega como MSG_FILE en la red (ver FileChunker);
     *  isImage se decide por mime_type, no por el opcode. */
    private void handleMetadata(Packet p) {
        Map<String, String> m = Json.decode(p.payloadAsString());
        String transferId = m.get("transfer_id");
        if (transferId == null) return;

        IncomingTransfer t = new IncomingTransfer();
        t.transferId = transferId;
        t.filename = m.getOrDefault("filename", "archivo_recibido");
        t.mimeType = m.getOrDefault("mime_type", "application/octet-stream");
        t.totalChunks = parseIntSafe(m.get("total_chunks"), 0);
        t.senderId = p.senderId();
        t.receiverId = p.receiverId();
        t.isImage = t.mimeType.startsWith("image/");
        active.put(transferId, t);
    }

    /** Chunk: payload = "transferId|chunkIndex|" + bytes crudos. */
    private void handleChunk(Packet p) {
        byte[] raw = p.payload();
        if (raw == null || raw.length < 3) return;

        int firstPipe = indexOf(raw, (byte) '|', 0);
        if (firstPipe < 0) return;
        int secondPipe = indexOf(raw, (byte) '|', firstPipe + 1);
        if (secondPipe < 0) return;

        String transferId = new String(raw, 0, firstPipe, StandardCharsets.UTF_8);
        String idxStr = new String(raw, firstPipe + 1, secondPipe - firstPipe - 1, StandardCharsets.UTF_8);
        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(idxStr);
        } catch (NumberFormatException e) {
            return;
        }

        IncomingTransfer t = active.get(transferId);
        if (t == null) return;

        byte[] data = new byte[raw.length - secondPipe - 1];
        System.arraycopy(raw, secondPipe + 1, data, 0, data.length);
        t.chunks.put(chunkIndex, data);
    }

    /** Fin: {"transfer_id","checksum_crc32"} -> ensambla y escribe a disco. */
    private void handleComplete(Packet p) {
        Map<String, String> m = Json.decode(p.payloadAsString());
        String transferId = m.get("transfer_id");
        if (transferId == null) return;

        IncomingTransfer t = active.remove(transferId);
        if (t == null) return;

        try {
            File outDir = new File("received_files");
            outDir.mkdirs();
            File outFile = new File(outDir, transferId + "_" + t.filename);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                for (int i = 0; i < t.totalChunks; i++) {
                    byte[] chunk = t.chunks.get(i);
                    if (chunk != null) fos.write(chunk);
                }
            }
            listener.onFileReceived(t.senderId, t.receiverId, t.isImage, outFile);
        } catch (IOException e) {
            listener.onError("Error guardando archivo recibido: " + e.getMessage());
        }
    }

    private static int indexOf(byte[] arr, byte target, int from) {
        for (int i = from; i < arr.length; i++) if (arr[i] == target) return i;
        return -1;
    }

    private static int parseIntSafe(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}