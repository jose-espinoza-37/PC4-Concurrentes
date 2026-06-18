package dogmsg.client;

import dogmsg.client.ui.ChatListView;
import dogmsg.client.ui.ChatWindow;
import dogmsg.client.ui.GroupDialog;
import dogmsg.client.ui.LoginView;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Punto de entrada del cliente desktop (Java + Swing).
 *
 * <p>Uso: {@code java -jar client-desktop.jar [--host IP] [--port 9000] [--file-port 9001]}</p>
 *
 * <p>Cablea {@link LoginView}, {@link ChatListView}, {@link ChatWindow},
 * {@link GroupDialog}, {@link QRManager} y {@link FileChunker} sobre el
 * {@link ChatController}. Todas las actualizaciones de UI ocurren en el EDT.</p>
 */
public class DogMessengerApp implements ChatController.UiCallbacks {

    private final String host;
    private final int port;
    private final int filePort;

    private final JFrame frame = new JFrame("Dog Messenger - Desktop");
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);

    private LoginView loginView;
    private ChatListView chatList;
    private ChatWindow chatWindow;
    private JLabel statusBar;

    private ChatController controller;
    private LocalCache cache;
    private final QRManager qr = new QRManager();
    private FileChunker fileChunker;
    private FileReceiver fileReceiver;

    // conversacion activa
    private long currentPeer = -1;
    private boolean currentIsGroup = false;
    private String currentTitle = "";

    public DogMessengerApp(String host, int port, int filePort) {
        this.host = host;
        this.port = port;
        this.filePort = filePort;
    }

    public void start() throws Exception {
        cache = new LocalCache(System.getProperty("user.home") + File.separator + ".dogmsg-desktop-cache.db");
        controller = new ChatController(host, port, cache, "desktop");
        controller.setUi(this);
        fileChunker = new FileChunker(host, filePort, controller::buildFileChannelAuthPayload);
        fileReceiver = new FileReceiver(host, filePort, controller::buildFileChannelAuthPayload,
                new FileReceiver.Listener() {
                    @Override
                    public void onFileReceived(long senderId, long receiverId, boolean isImage, File savedFile) {
                        SwingUtilities.invokeLater(() -> {
                            chatList.upsert(senderId, false, "Usuario " + senderId,
                                    isImage ? "\uD83D\uDDBC Imagen" : "\uD83D\uDCCE " + savedFile.getName(),
                                    System.currentTimeMillis());
                            boolean isCurrent = senderId == currentPeer && !currentIsGroup;
                            if (!isCurrent) {
                                openConversation(new ChatListView.Conversation(senderId, false, "Usuario " + senderId));
                                chatList.select(senderId, false);
                            }
                            if (isImage) {
                                chatWindow.addImageMessage(senderId, null, savedFile,
                                        System.currentTimeMillis(), "delivered");
                            } else {
                                chatWindow.addFileMessage(senderId, null, savedFile,
                                        System.currentTimeMillis(), "delivered");
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        SwingUtilities.invokeLater(() -> onSystem(message));
                    }
                });

        buildLogin();
        buildChat();

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                controller.shutdown();
                cache.close();
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setContentPane(root);
        frame.setSize(820, 560);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        controller.start();
    }

    private void buildLogin() {
        loginView = new LoginView(
                (u, p) -> controller.authenticate(u, p, false),
                (u, p) -> controller.authenticate(u, p, true));
        root.add(loginView, "login");
        cards.show(root, "login");
    }

    private void buildChat() {
        JPanel chatPanel = new JPanel(new BorderLayout());

        // Barra de herramientas
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton newConv = new JButton("Nueva conversacion");
        JButton newGroup = new JButton("Nuevo grupo");
        JButton cloneBtn = new JButton("Clonar dispositivo (QR)");
        JButton scanBtn = new JButton("Escanear QR");
        bar.add(newConv);
        bar.add(newGroup);
        bar.addSeparator();
        bar.add(cloneBtn);
        bar.add(scanBtn);
        chatPanel.add(bar, BorderLayout.NORTH);

        chatList = new ChatListView(this::openConversation);
        chatWindow = new ChatWindow();
        chatWindow.setOnSend(this::sendCurrent);
        chatWindow.setOnAttachFile(() -> attach(false));
        chatWindow.setOnAttachImage(() -> attach(true));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatList, chatWindow);
        split.setDividerLocation(260);
        chatPanel.add(split, BorderLayout.CENTER);

        statusBar = new JLabel(" Desconectado");
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        chatPanel.add(statusBar, BorderLayout.SOUTH);

        newConv.addActionListener(e -> newConversationDialog());
        newGroup.addActionListener(e -> new GroupDialog(frame, this::createGroup).setVisible(true));
        cloneBtn.addActionListener(e -> controller.requestQrToken());
        scanBtn.addActionListener(e -> scanQrFromFile());

        root.add(chatPanel, "chat");
    }

    // ---------------- acciones UI ----------------

    private void newConversationDialog() {
        String idStr = JOptionPane.showInputDialog(frame,
                "ID de usuario con quien chatear:", "Nueva conversacion",
                JOptionPane.QUESTION_MESSAGE);
        if (idStr == null) return;
        try {
            long peer = Long.parseLong(idStr.trim());
            chatList.upsert(peer, false, "Usuario " + peer, "", System.currentTimeMillis());
            controller.startKeyExchange(peer); // preparar E2E
            openConversation(new ChatListView.Conversation(peer, false, "Usuario " + peer));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(frame, "ID invalido.");
        }
    }

    private void createGroup(String name, List<String> memberIdStrings) {
        java.util.List<Long> memberIds = new java.util.ArrayList<>();
        for (String s : memberIdStrings) {
            try { memberIds.add(Long.parseLong(s.trim())); }
            catch (NumberFormatException ignored) { /* ya validado en GroupDialog */ }
        }
        controller.createGroup(name, memberIds);
        onSystem("Grupo '" + name + "' solicitado (" + memberIds.size() + " miembros).");
    }

    private void openConversation(ChatListView.Conversation c) {
        currentPeer = c.id;
        currentIsGroup = c.isGroup;
        currentTitle = c.title;
        chatWindow.setMyUserId(controller.myUserId());
        chatWindow.openConversation(c.title);
        try {
            for (LocalCache.CachedMessage m : cache.recent(c.id, c.isGroup, 50)) {
                String name = m.senderId == controller.myUserId() ? null : ("Usuario " + m.senderId);
                chatWindow.addMessage(m.senderId, name, m.text, m.timestamp, m.status);
            }
        } catch (Exception e) {
            onSystem("Error cargando cache: " + e.getMessage());
        }
    }

    private void sendCurrent(String text) {
        if (currentPeer < 0) return;
        controller.sendText(currentPeer, currentIsGroup, text);
        chatWindow.addMessage(controller.myUserId(), null, text, System.currentTimeMillis(), "sent");
        chatList.upsert(currentPeer, currentIsGroup, currentTitle, text, System.currentTimeMillis());
    }

    private void attach(boolean imageOnly) {
        if (currentPeer < 0) {
            JOptionPane.showMessageDialog(frame, "Abre una conversacion primero.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        if (imageOnly) {
            fc.setDialogTitle("Selecciona una imagen");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Imagenes (jpg, jpeg, png, gif, webp)",
                    "jpg", "jpeg", "png", "gif", "webp"));
        } else {
            fc.setDialogTitle("Selecciona un archivo");
        }
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();

        JProgressBar progress = new JProgressBar(0, 100);
        JDialog dlg = new JDialog(frame, "Enviando " + file.getName(), false);
        dlg.add(progress);
        dlg.setSize(320, 70);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        new Thread(() -> {
            try {
                fileChunker.sendFile(controller.myUserId(), currentPeer, file, imageOnly,
                        pct -> SwingUtilities.invokeLater(() -> progress.setValue(pct)));
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    if (imageOnly) {
                        chatWindow.addImageMessage(controller.myUserId(), null, file,
                                System.currentTimeMillis(), "sent");
                    } else {
                        chatWindow.addFileMessage(controller.myUserId(), null, file,
                                System.currentTimeMillis(), "sent");
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    JOptionPane.showMessageDialog(frame, "Error enviando " +
                            (imageOnly ? "imagen" : "archivo") + ": " + ex.getMessage());
                });
            }
        }, "file-send").start();
    }

    private void scanQrFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Selecciona una imagen con el codigo QR");
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            String token = qr.readFromFile(fc.getSelectedFile());
            controller.validateQr(token);
            onSystem("QR leido, validando token con el servidor...");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "No se pudo leer el QR: " + e.getMessage());
        }
    }

    // ---------------- callbacks del controlador ----------------

    @Override
    public void onConnectionState(boolean connected, String detail) {
        statusBar.setText(connected ? " Conectado a " + host + ":" + port
                                    : " Desconectado: " + detail);
    }

    @Override
    public void onAuthResult(boolean ok, long userId, String tokenOrError) {
        if (ok) {
            cards.show(root, "chat");
            onSystem("Sesion iniciada. Tu ID: " + userId);
            fileReceiver.start();
        } else {
            loginView.showError(tokenOrError);
        }
    }

    @Override
    public void onTextMessage(long peerId, boolean isGroup, long senderId, String text, long timestamp) {
        String title = isGroup ? "Grupo " + peerId : "Usuario " + peerId;
        chatList.upsert(peerId, isGroup, title, text, timestamp);
        boolean isCurrent = (peerId == currentPeer && isGroup == currentIsGroup);
        if (!isCurrent) {
            // Antes: si el receptor nunca habia hecho clic en esa conversacion,
            // el mensaje quedaba solo en la lista (preview) sin mostrarse nunca
            // en el panel de chat ni recargar el cache. Ahora se abre/selecciona
            // automaticamente la conversacion entrante para que el mensaje sea
            // visible de inmediato, sin requerir accion manual del usuario.
            openConversation(new ChatListView.Conversation(peerId, isGroup, title));
            chatList.select(peerId, isGroup);
        } else {
            String name = isGroup ? "Usuario " + senderId : null;
            chatWindow.addMessage(senderId, name, text, timestamp, "delivered");
        }
    }

    @Override
    public void onAck(long sequence) {
        // El estado pasa a 'delivered'. (La asociacion seq->mensaje se podria
        // refinar guardando el id local devuelto por LocalCache.)
    }

    @Override
    public void onAddedToGroup(long groupId, long byUserId, String groupName) {
        String title = (groupName != null && !groupName.isBlank()) ? groupName : "Grupo " + groupId;
        chatList.upsert(groupId, true, title,
                "Fuiste agregado por usuario " + byUserId, System.currentTimeMillis());
        onSystem("Te agregaron al grupo '" + title + "'. Ya aparece en tu lista de chats.");
    }

    @Override
    public void onGroupCreated(long groupId, String name) {
        chatList.upsert(groupId, true, name, "Grupo creado", System.currentTimeMillis());
        onSystem("Grupo '" + name + "' creado con ID " + groupId + ".");
    }

    @Override
    public void onQrToken(String token, int expiresInSeconds) {
        try {
            BufferedImage img = qr.generate(token, 280);
            JLabel pic = new JLabel(new ImageIcon(img));
            JOptionPane.showMessageDialog(frame, pic,
                    "Escanea este QR con el otro dispositivo (" + expiresInSeconds + "s)",
                    JOptionPane.PLAIN_MESSAGE);
        } catch (Exception e) {
            onSystem("Error generando QR: " + e.getMessage());
        }
    }

    @Override
    public void onQrValidated(boolean ok, String message) {
        if (ok) {
            onSystem("QR validado: " + message);
        } else {
            JOptionPane.showMessageDialog(frame, message, "QR invalido",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    @Override
    public void onHistorySynced(int count) {
        onSystem("Historial sincronizado (" + count + " bytes recibidos).");
    }

    @Override
    public void onSystem(String message) {
        statusBar.setText(" " + message);
    }

    // ---------------- main ----------------

    public static void main(String[] args) {
        String host = "localhost";
        int port = 9000;
        int filePort = 9001;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host":      host = args[++i]; break;
                case "--port":      port = Integer.parseInt(args[++i]); break;
                case "--file-port": filePort = Integer.parseInt(args[++i]); break;
            }
        }
        final String fHost = host;
        final int fPort = port, fFilePort = filePort;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            try {
                new DogMessengerApp(fHost, fPort, fFilePort).start();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Error al iniciar: " + e.getMessage());
            }
        });
    }
}