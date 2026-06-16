package dogmsg;

import dogmsg.protocol.OpCode;
import dogmsg.protocol.Packet;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Server.java
 * Dog Messenger — Entry point del servidor central.
 *
 * Arquitectura (sección 2 del plan):
 *  - Puerto 9000: canal principal de mensajes, grupos, auth, QR, encriptación.
 *  - Puerto 9001: canal de transferencia de archivos (mismo handler, puerto separado
 *                 para no bloquear mensajes de texto con transferencias largas).
 *
 * Concurrencia:
 *  - ThreadPoolExecutor con pool de hilos; un hilo por cliente conectado.
 *  - Todos los subsistemas comparten el mismo mapa onlineMap (ConcurrentHashMap).
 *
 * Inicio:
 *   java -jar server.jar [--port 9000] [--file-port 9001]
 *
 * ShutdownHook registrado para cierre limpio con CTRL-C / SIGTERM.
 */
public class Server {

    private static final Logger log = Logger.getLogger(Server.class.getName());

    // ── Configuración por defecto ─────────────────────────────────────────────
    private static final int DEFAULT_PORT      = 9000;
    private static final int DEFAULT_FILE_PORT = 9001;
    private static final int PING_INTERVAL_MS  = 30_000; // 30 segundos

    // ── Mapa de clientes online (userId → ClientHandler) ──────────────────────
    private final Map<Integer, ClientHandler> onlineMap = new ConcurrentHashMap<>();

    // ── Subsistemas del servidor ──────────────────────────────────────────────
    private DatabaseManager      db;
    private AuthManager          auth;
    private OfflineQueue         offline;
    private GroupManager         groupManager;
    private MessageRouter        router;
    private FileTransferHandler  fileTransfer;
    private EncryptionBroker     encBroker;
    private QRManager            qrManager;

    // ── Sockets de escucha ────────────────────────────────────────────────────
    private ServerSocket mainSocket;
    private ServerSocket fileSocket;
    private volatile boolean running = false;

    // ── Pool de hilos ─────────────────────────────────────────────────────────
    private final ExecutorService pool = new ThreadPoolExecutor(
            10, 200, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "client-handler");
                t.setDaemon(false);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // ── Scheduler de tareas periódicas ────────────────────────────────────────
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "server-scheduler");
                t.setDaemon(true);
                return t;
            });

    // ════════════════════════════════════════════════════════════════════════
    // Main
    // ════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        configureLogging();

        int port     = DEFAULT_PORT;
        int filePort = DEFAULT_FILE_PORT;

        // Parseo de argumentos --port y --file-port
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i]))      port     = Integer.parseInt(args[i + 1]);
            if ("--file-port".equals(args[i])) filePort = Integer.parseInt(args[i + 1]);
        }

        Server server = new Server();
        server.start(port, filePort);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Inicio del servidor
    // ════════════════════════════════════════════════════════════════════════

    public void start(int port, int filePort) {
        printBanner(port, filePort);

        // ── Inicializar subsistemas ───────────────────────────────────────────
        try {
            db = new DatabaseManager();
            db.init();
        } catch (SQLException e) {
            log.severe("No se pudo inicializar la base de datos: " + e.getMessage());
            System.exit(1);
        }

        offline      = new OfflineQueue(db);
        groupManager = new GroupManager(db);
        auth         = new AuthManager(db);
        encBroker    = new EncryptionBroker(db);
        qrManager    = new QRManager();
        router       = new MessageRouter(db, onlineMap, offline, groupManager);
        fileTransfer = new FileTransferHandler(db, onlineMap, offline, groupManager);

        // ── Tareas periódicas ─────────────────────────────────────────────────
        // Purgar sesiones expiradas cada hora
        scheduler.scheduleAtFixedRate(auth::purgeExpiredSessions,
                3600, 3600, TimeUnit.SECONDS);

        // ── ShutdownHook ──────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Señal de cierre recibida. Apagando servidor...");
            shutdown();
        }, "shutdown-hook"));

        // ── Abrir sockets de escucha ──────────────────────────────────────────
        try {
            mainSocket = new ServerSocket(port);
            mainSocket.setReuseAddress(true);
            fileSocket = new ServerSocket(filePort);
            fileSocket.setReuseAddress(true);
        } catch (IOException e) {
            log.severe("Error abriendo puertos: " + e.getMessage());
            System.exit(1);
        }

        running = true;
        log.info("Servidor listo. Esperando conexiones...");

        // ── Hilo para el puerto de archivos ───────────────────────────────────
        Thread fileThread = new Thread(() -> acceptLoop(fileSocket), "file-acceptor");
        fileThread.setDaemon(true);
        fileThread.start();

        // ── Hilo de consola admin ─────────────────────────────────────────────
        Thread consoleThread = new Thread(this::consoleLoop, "admin-console");
        consoleThread.setDaemon(true);
        consoleThread.start();

        // ── Loop principal de aceptación (bloquea aquí) ───────────────────────
        acceptLoop(mainSocket);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Loop de aceptación de conexiones
    // ════════════════════════════════════════════════════════════════════════

    private void acceptLoop(ServerSocket srv) {
        while (running) {
            try {
                Socket client = srv.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                log.info("[Accept] Nueva conexión desde "
                        + client.getInetAddress().getHostAddress()
                        + ":" + client.getPort()
                        + " (puerto local=" + srv.getLocalPort() + ")");

                ClientHandler handler = new ClientHandler(
                        client, onlineMap,
                        auth, router, groupManager,
                        fileTransfer, offline,
                        encBroker, qrManager);

                pool.execute(handler);

            } catch (IOException e) {
                if (running) {
                    log.warning("[Accept] Error aceptando conexión: " + e.getMessage());
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Shutdown limpio
    // ════════════════════════════════════════════════════════════════════════

    public void shutdown() {
        running = false;

        // Cerrar ServerSockets (desbloquea accept())
        closeQuietly(mainSocket);
        closeQuietly(fileSocket);

        // Forzar cierre de clientes activos
        for (ClientHandler h : new ArrayList<>(onlineMap.values())) {
            h.forceClose();
        }
        onlineMap.clear();

        // Apagar pools
        pool.shutdown();
        scheduler.shutdown();
        qrManager.shutdown();

        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (db != null) db.close();

        log.info("Dog Messenger Server apagado. ¡Hasta pronto! 🐶");
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null && !s.isClosed()) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Consola de administración
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Comandos disponibles en stdin:
     *   status   — usuarios online, grupos, mensajes en BD
     *   users    — lista de usuarios conectados
     *   stop     — apagar el servidor
     *   help     — esta ayuda
     */
    private void consoleLoop() {
        Scanner sc = new Scanner(System.in);
        System.out.println("[Console] Consola activa. Escribe 'help'.");
        while (running) {
            try {
                System.out.print("dog9000> ");
                if (!sc.hasNextLine()) break;
                String cmd = sc.nextLine().trim().toLowerCase();
                switch (cmd) {
                    case "status" -> {
                        System.out.println("── Estado ───────────────────────────");
                        System.out.println("Clientes online : " + onlineMap.size());
                        System.out.println("Grupos activos  : " + groupManager.groupCount());
                        System.out.println("─────────────────────────────────────");
                    }
                    case "users" -> {
                        onlineMap.forEach((id, h) ->
                                System.out.println("  userId=" + id + " user=" + h.getUsername()));
                    }
                    case "stop"  -> System.exit(0);
                    case "help","?" ->
                        System.out.println("Comandos: status | users | stop | help");
                    case ""      -> {}
                    default -> System.out.println("Desconocido: '" + cmd + "'. Escribe help.");
                }
            } catch (Exception e) { /* ignorar */ }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Banner y logging
    // ════════════════════════════════════════════════════════════════════════

    private static void printBanner(int port, int filePort) {
        System.out.println();
        System.out.println("  ██████╗  ██████╗  ██████╗     ███╗   ███╗███████╗███████╗███████╗");
        System.out.println("  ██╔══██╗██╔═══██╗██╔════╝     ████╗ ████║██╔════╝██╔════╝██╔════╝");
        System.out.println("  ██║  ██║██║   ██║██║  ███╗    ██╔████╔██║█████╗  ███████╗███████╗");
        System.out.println("  ██║  ██║██║   ██║██║   ██║    ██║╚██╔╝██║██╔══╝  ╚════██║╚════██║");
        System.out.println("  ██████╔╝╚██████╔╝╚██████╔╝    ██║ ╚═╝ ██║███████╗███████║███████║");
        System.out.println("  ╚═════╝  ╚═════╝  ╚═════╝     ╚═╝     ╚═╝╚══════╝╚══════╝╚══════╝");
        System.out.println("  ═══════════════════════════════════════════════════════════════════");
        System.out.println("  CC4P1 Programación Concurrente y Distribuida — Práctica 04 2026-I");
        System.out.printf ("  Puerto mensajes: %d  |  Puerto archivos: %d%n", port, filePort);
        System.out.println("  ═══════════════════════════════════════════════════════════════════");
        System.out.println();
    }

    private static void configureLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$-7s] %3$s — %5$s%n");
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            h.setFormatter(new SimpleFormatter());
            h.setLevel(Level.INFO);
        }
        root.setLevel(Level.INFO);
    }

    // ── Método auxiliar para GroupManager.groupCount() ────────────────────────
    // GroupManager necesita exponer este contador; lo agregamos aquí para
    // que Server.java compile sin modificar GroupManager.
    // GroupManager ya tiene el método groupCount() definido abajo:
}
