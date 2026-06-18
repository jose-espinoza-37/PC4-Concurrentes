package dogmsg.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;

/**
 * T-B3: Ventana de chat con historial desplazable, burbujas de texto e
 * indicadores de estado (enviado = check, recibido = doble check). Incluye el
 * input de texto y botones para adjuntar imagen/archivo (T-B6).
 */
public class ChatWindow extends JPanel {

    private final JPanel messages = new JPanel();
    private final JScrollPane scroll;
    private final JTextField input = new JTextField();
    private final JLabel title = new JLabel("Selecciona una conversacion");
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm");

    private long myUserId;
    private Consumer<String> onSend = t -> {};
    private Runnable onAttachFile = () -> {};
    private Runnable onAttachImage = () -> {};

    public ChatWindow() {
        setLayout(new BorderLayout());

        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setBorder(new EmptyBorder(10, 12, 10, 12));
        add(title, BorderLayout.NORTH);

        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBackground(new Color(0xF2F4F7));
        scroll = new JScrollPane(messages);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.setBorder(new EmptyBorder(8, 8, 8, 8));

        JButton imgBtn = new JButton("\uD83D\uDDBC"); // imagen
        JButton fileBtn = new JButton("\uD83D\uDCCE"); // adjuntar
        imgBtn.setToolTipText("Adjuntar imagen");
        fileBtn.setToolTipText("Adjuntar archivo");
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(imgBtn);
        left.add(fileBtn);

        JButton sendBtn = new JButton("Enviar");
        bottom.add(left, BorderLayout.WEST);
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(sendBtn, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        Runnable doSend = () -> {
            String t = input.getText().trim();
            if (!t.isEmpty()) {
                onSend.accept(t);
                input.setText("");
            }
        };
        sendBtn.addActionListener(e -> doSend.run());
        input.addActionListener(e -> doSend.run());
        imgBtn.addActionListener(e -> onAttachImage.run());
        fileBtn.addActionListener(e -> onAttachFile.run());

        setInputEnabled(false);
    }

    public void setMyUserId(long id) { this.myUserId = id; }
    public void setOnSend(Consumer<String> cb) { this.onSend = cb; }
    public void setOnAttachFile(Runnable cb) { this.onAttachFile = cb; }
    public void setOnAttachImage(Runnable cb) { this.onAttachImage = cb; }

    public void openConversation(String convTitle) {
        title.setText(convTitle);
        messages.removeAll();
        messages.revalidate();
        messages.repaint();
        setInputEnabled(true);
    }

    private void setInputEnabled(boolean en) {
        input.setEnabled(en);
    }

    /**
     * Anade una burbuja de mensaje.
     *
     * @param senderId   autor del mensaje
     * @param senderName nombre a mostrar (en grupos); null para 1-a-1
     * @param text       contenido
     * @param ts         timestamp en millis
     * @param status     "sent" | "delivered" | "read" (solo para mensajes propios)
     */
    public void addMessage(long senderId, String senderName, String text, long ts, String status) {
        boolean mine = senderId == myUserId;

        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);

        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(new EmptyBorder(6, 10, 6, 10));
        bubble.setBackground(mine ? new Color(0xD2F8C6) : Color.WHITE);

        if (!mine && senderName != null) {
            JLabel who = new JLabel(senderName);
            who.setFont(who.getFont().deriveFont(Font.BOLD, 11f));
            who.setForeground(new Color(0x3949AB));
            bubble.add(who);
        }

        JLabel body = new JLabel("<html><body style='width:240px'>" + escapeHtml(text) + "</body></html>");
        bubble.add(body);

        String meta = fmt.format(new Date(ts));
        if (mine) meta += "  " + statusGlyph(status);
        JLabel metaLbl = new JLabel(meta);
        metaLbl.setFont(metaLbl.getFont().deriveFont(10f));
        metaLbl.setForeground(Color.GRAY);
        metaLbl.setAlignmentX(Component.RIGHT_ALIGNMENT);
        bubble.add(metaLbl);

        row.add(bubble);
        messages.add(row);
        messages.revalidate();

        // autoscroll al final
        SwingUtilities.invokeLater(() -> {
            JScrollBar v = scroll.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    /**
     * Anade una burbuja con la miniatura de una imagen (T-B6 / MSG_IMAGE).
     *
     * @param senderId   autor del mensaje
     * @param senderName nombre a mostrar (en grupos); null para 1-a-1
     * @param imageFile  archivo de imagen ya disponible localmente en disco
     *                   (al enviar: el archivo elegido; al recibir: el archivo
     *                   reconstruido por FileChunker/receptor de archivos)
     * @param ts         timestamp en millis
     * @param status     "sent" | "delivered" | "read" (solo para mensajes propios)
     */
    public void addImageMessage(long senderId, String senderName, java.io.File imageFile,
                                 long ts, String status) {
        boolean mine = senderId == myUserId;

        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);

        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(new EmptyBorder(6, 10, 6, 10));
        bubble.setBackground(mine ? new Color(0xD2F8C6) : Color.WHITE);

        if (!mine && senderName != null) {
            JLabel who = new JLabel(senderName);
            who.setFont(who.getFont().deriveFont(Font.BOLD, 11f));
            who.setForeground(new Color(0x3949AB));
            bubble.add(who);
        }

        JLabel pic = new JLabel();
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imageFile);
            if (img != null) {
                int maxW = 220;
                int w = img.getWidth(), h = img.getHeight();
                double scale = Math.min(1.0, maxW / (double) w);
                int sw = Math.max(1, (int) (w * scale));
                int sh = Math.max(1, (int) (h * scale));
                java.awt.Image scaled = img.getScaledInstance(sw, sh, java.awt.Image.SCALE_SMOOTH);
                pic.setIcon(new ImageIcon(scaled));
            } else {
                pic.setText("\uD83D\uDDBC " + imageFile.getName() + " (no se pudo previsualizar)");
            }
        } catch (Exception e) {
            pic.setText("\uD83D\uDDBC " + imageFile.getName() + " (error al leer)");
        }
        pic.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.add(pic);

        JLabel nameLbl = new JLabel(imageFile.getName());
        nameLbl.setFont(nameLbl.getFont().deriveFont(10f));
        nameLbl.setForeground(Color.DARK_GRAY);
        bubble.add(nameLbl);

        JButton saveBtn = new JButton("\u2B07 Guardar como...");
        saveBtn.setFont(saveBtn.getFont().deriveFont(10f));
        saveBtn.setMargin(new Insets(2, 4, 2, 4));
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.addActionListener(e -> saveFileAs(imageFile));
        bubble.add(saveBtn);

        String meta = fmt.format(new Date(ts));
        if (mine) meta += "  " + statusGlyph(status);
        JLabel metaLbl = new JLabel(meta);
        metaLbl.setFont(metaLbl.getFont().deriveFont(10f));
        metaLbl.setForeground(Color.GRAY);
        metaLbl.setAlignmentX(Component.RIGHT_ALIGNMENT);
        bubble.add(metaLbl);

        row.add(bubble);
        messages.add(row);
        messages.revalidate();

        SwingUtilities.invokeLater(() -> {
            JScrollBar v = scroll.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    /**
     * Anade una burbuja para un archivo no-imagen (T-B6 / MSG_FILE), con boton
     * para guardarlo en disco. Sin esto, el archivo recibido quedaba solo en
     * la carpeta interna {@code received_files/} sin forma de exportarlo.
     *
     * @param senderId   autor del mensaje
     * @param senderName nombre a mostrar (en grupos); null para 1-a-1
     * @param file       archivo ya disponible localmente en disco
     * @param ts         timestamp en millis
     * @param status     "sent" | "delivered" | "read" (solo para mensajes propios)
     */
    public void addFileMessage(long senderId, String senderName, java.io.File file,
                                long ts, String status) {
        boolean mine = senderId == myUserId;

        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);

        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(new EmptyBorder(6, 10, 6, 10));
        bubble.setBackground(mine ? new Color(0xD2F8C6) : Color.WHITE);

        if (!mine && senderName != null) {
            JLabel who = new JLabel(senderName);
            who.setFont(who.getFont().deriveFont(Font.BOLD, 11f));
            who.setForeground(new Color(0x3949AB));
            bubble.add(who);
        }

        JLabel nameLbl = new JLabel("\uD83D\uDCCE " + file.getName());
        nameLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.add(nameLbl);

        JButton saveBtn = new JButton("\u2B07 Guardar como...");
        saveBtn.setFont(saveBtn.getFont().deriveFont(10f));
        saveBtn.setMargin(new Insets(2, 4, 2, 4));
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.addActionListener(e -> saveFileAs(file));
        bubble.add(saveBtn);

        String meta = fmt.format(new Date(ts));
        if (mine) meta += "  " + statusGlyph(status);
        JLabel metaLbl = new JLabel(meta);
        metaLbl.setFont(metaLbl.getFont().deriveFont(10f));
        metaLbl.setForeground(Color.GRAY);
        metaLbl.setAlignmentX(Component.RIGHT_ALIGNMENT);
        bubble.add(metaLbl);

        row.add(bubble);
        messages.add(row);
        messages.revalidate();

        SwingUtilities.invokeLater(() -> {
            JScrollBar v = scroll.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    /** Copia {@code source} a la ubicacion que el usuario elija. */
    private void saveFileAs(java.io.File source) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(source.getName()));
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        java.io.File dest = fc.getSelectedFile();
        try {
            java.nio.file.Files.copy(source.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JOptionPane.showMessageDialog(owner, "Guardado en: " + dest.getAbsolutePath());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(owner, "Error al guardar: " + ex.getMessage());
        }
    }

    /** enviado = un check, recibido = doble check, leido = doble check azul (texto). */
    private String statusGlyph(String status) {
        if (status == null) return "\u2713";
        switch (status) {
            case "delivered": return "\u2713\u2713";
            case "read":      return "\u2713\u2713 (leido)";
            default:          return "\u2713";
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}