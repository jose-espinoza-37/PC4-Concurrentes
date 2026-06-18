package dogmsg.client.ui;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;


public class ChatListView extends JPanel {

    /** Resumen de una conversacion en la lista. */
    public static class Conversation {
        public final long id;
        public final boolean isGroup;
        public String title;
        public String lastMessage = "";
        public long lastTimestamp = 0;

        public Conversation(long id, boolean isGroup, String title) {
            this.id = id;
            this.isGroup = isGroup;
            this.title = title;
        }
    }

    private final DefaultListModel<Conversation> model = new DefaultListModel<>();
    private final JList<Conversation> list = new JList<>(model);
    private final Map<String, Integer> indexByKey = new LinkedHashMap<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm");

    public ChatListView(Consumer<Conversation> onSelect) {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(260, 0));

        JLabel header = new JLabel("  Conversaciones");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
        header.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        add(header, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Conversation c = list.getSelectedValue();
                if (c != null) onSelect.accept(c);
            }
        });
        add(new JScrollPane(list), BorderLayout.CENTER);
    }

    private String keyOf(long id, boolean group) {
        return (group ? "g" : "u") + id;
    }

    /** Agrega o actualiza una conversacion en la lista. */
    public void upsert(long id, boolean isGroup, String title, String lastMessage, long ts) {
        String key = keyOf(id, isGroup);
        Integer idx = indexByKey.get(key);
        if (idx == null) {
            Conversation c = new Conversation(id, isGroup, title);
            c.lastMessage = lastMessage;
            c.lastTimestamp = ts;
            model.addElement(c);
            indexByKey.put(key, model.size() - 1);
        } else {
            Conversation c = model.get(idx);
            if (title != null) c.title = title;
            c.lastMessage = lastMessage;
            c.lastTimestamp = ts;
            model.set(idx, c);
        }
    }

    /** Selecciona programaticamente una conversacion (ej. al recibir un mensaje nuevo). */
    public void select(long id, boolean isGroup) {
        Integer idx = indexByKey.get(keyOf(id, isGroup));
        if (idx != null) {
            list.setSelectedIndex(idx);
        }
    }

    private class Renderer extends JPanel implements ListCellRenderer<Conversation> {
        private final JLabel titleLbl = new JLabel();
        private final JLabel msgLbl = new JLabel();
        private final JLabel timeLbl = new JLabel();

        Renderer() {
            setLayout(new BorderLayout(4, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD));
            msgLbl.setForeground(Color.GRAY);
            text.add(titleLbl);
            text.add(msgLbl);
            add(text, BorderLayout.CENTER);
            timeLbl.setForeground(Color.GRAY);
            add(timeLbl, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Conversation> l,
                Conversation c, int index, boolean selected, boolean focused) {
            String icon = c.isGroup ? "\uD83D\uDC65 " : "\uD83D\uDC64 "; // grupo : individuo
            titleLbl.setText(icon + c.title);
            String m = c.lastMessage == null ? "" : c.lastMessage;
            if (m.length() > 32) m = m.substring(0, 32) + "...";
            msgLbl.setText(m);
            timeLbl.setText(c.lastTimestamp > 0 ? fmt.format(new Date(c.lastTimestamp)) : "");
            setBackground(selected ? new Color(0xDDE7FF) : Color.WHITE);
            setOpaque(true);
            return this;
        }
    }
}