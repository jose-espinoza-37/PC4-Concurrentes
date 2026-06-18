package dogmsg.client;

import dogmsg.client.protocol.OpCode;
import dogmsg.client.protocol.Packet;
import dogmsg.client.protocol.PacketParser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SocketManager {

    /** Callback hacia la capa de UI / controlador. */
    public interface Listener {
        void onConnected();
        void onPacket(Packet packet);
        void onDisconnected(String reason);
    }

    private final String host;
    private final int port;
    private final Listener listener;

    private volatile Socket socket;
    private volatile OutputStream out;
    private final Object writeLock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(1);

    private volatile long myUserId = 0; // se setea tras AUTH_RESPONSE
    private Thread readerThread;
    private ScheduledExecutorService keepAlive;

    private static final int PING_INTERVAL_SEC = 30;
    private static final long MAX_BACKOFF_MS = 30_000;

    public SocketManager(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public void setMyUserId(long id) { this.myUserId = id; }
    public long myUserId() { return myUserId; }
    public boolean isConnected() { return connected.get(); }

    /** Numero de secuencia incremental para correlacionar ACKs. */
    public long nextSequence() { return sequence.getAndIncrement(); }

    /** Arranca la conexion (no bloqueante): hilo de lectura + reconexion. */
    public void start() {
        if (running.getAndSet(true)) return;
        readerThread = new Thread(this::connectionLoop, "socket-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "keep-alive");
            t.setDaemon(true);
            return t;
        });
        keepAlive.scheduleAtFixedRate(this::sendPing,
                PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void connectionLoop() {
        long backoff = 500;
        while (running.get()) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setTcpNoDelay(true);
                this.socket = s;
                this.out = s.getOutputStream();
                connected.set(true);
                backoff = 500; // reset tras conexion exitosa
                listener.onConnected();

                PacketParser parser = new PacketParser(s.getInputStream());
                Packet pkt;
                while (running.get() && (pkt = parser.readPacket()) != null) {
                    listener.onPacket(pkt);
                }
                // stream cerrado limpiamente
                handleDisconnect("conexion cerrada por el servidor");
            } catch (IOException e) {
                handleDisconnect(e.getMessage());
            }

            if (!running.get()) break;

            // Backoff exponencial antes de reintentar.
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    private void handleDisconnect(String reason) {
        if (connected.getAndSet(false)) {
            closeSocketQuietly();
            listener.onDisconnected(reason == null ? "desconectado" : reason);
        }
    }

    /** Envia un paquete ya construido. Thread-safe. */
    public void send(Packet packet) throws IOException {
        OutputStream o = this.out;
        if (o == null || !connected.get()) {
            throw new IOException("No conectado");
        }
        byte[] bytes = packet.toBytes();
        synchronized (writeLock) {
            o.write(bytes);
            o.flush();
        }
    }

    /** Construye y envia un paquete con el id de usuario actual como remitente. */
    public long sendPacket(OpCode op, long receiverId, int flags, byte[] payload) throws IOException {
        long seq = nextSequence();
        Packet p = Packet.now(op, seq, myUserId, receiverId, flags, payload);
        send(p);
        return seq;
    }

    private void sendPing() {
        if (!connected.get()) return;
        try {
            sendPacket(OpCode.PING, 0, 0, new byte[0]);
        } catch (IOException ignored) {
            // el connectionLoop detectara la caida
        }
    }

    /** Cierre limpio: envia DISCONNECT y para todos los hilos. */
    public void shutdown() {
        running.set(false);
        try {
            if (connected.get()) {
                sendPacket(OpCode.DISCONNECT, 0, 0, new byte[0]);
            }
        } catch (IOException ignored) {}
        if (keepAlive != null) keepAlive.shutdownNow();
        closeSocketQuietly();
        if (readerThread != null) readerThread.interrupt();
    }

    private void closeSocketQuietly() {
        Socket s = this.socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        this.socket = null;
        this.out = null;
    }
}