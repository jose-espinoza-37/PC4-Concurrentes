package dogmsg.client.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;


public class GroupDialog extends JDialog {

    private final JTextField nameField = new JTextField(20);
    private final DefaultListModel<String> memberModel = new DefaultListModel<>();
    private final JTextField memberField = new JTextField(16);

    /**
     * @param owner     ventana padre
     * @param onCreate  callback (nombreGrupo, listaDeIdsDeUsuario)
     */
    public GroupDialog(Frame owner, BiConsumer<String, List<String>> onCreate) {
        super(owner, "Nuevo grupo", true);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Nombre del grupo:"));
        top.add(nameField);
        add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("Agregar miembro");
        addRow.add(new JLabel("ID de usuario:"));
        addRow.add(memberField);
        addRow.add(addBtn);
        center.add(addRow, BorderLayout.NORTH);
        center.add(new JScrollPane(new JList<>(memberModel)), BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton create = new JButton("Crear grupo");
        JButton cancel = new JButton("Cancelar");
        buttons.add(cancel);
        buttons.add(create);
        add(buttons, BorderLayout.SOUTH);

        addBtn.addActionListener(e -> {
            String u = memberField.getText().trim();
            if (u.isEmpty()) return;
            try {
                Long.parseLong(u);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "El ID de usuario debe ser numerico.");
                return;
            }
            if (!contains(u)) {
                memberModel.addElement(u);
                memberField.setText("");
            }
        });
        memberField.addActionListener(e -> addBtn.doClick());

        cancel.addActionListener(e -> dispose());
        create.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "El nombre del grupo es obligatorio.");
                return;
            }
            List<String> members = new ArrayList<>();
            for (int i = 0; i < memberModel.size(); i++) members.add(memberModel.get(i));
            onCreate.accept(name, members);
            dispose();
        });

        pack();
        setLocationRelativeTo(owner);
    }

    private boolean contains(String u) {
        for (int i = 0; i < memberModel.size(); i++) {
            if (memberModel.get(i).equalsIgnoreCase(u)) return true;
        }
        return false;
    }
}