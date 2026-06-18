package dogmsg.client.ui;

import javax.swing.*;
import java.awt.*;
import java.util.function.BiConsumer;

/**
 * T-B2: Formulario de login / registro.
 *
 * <p>Panel con campos usuario/contrasena, botones de login y registro, y un
 * area para mostrar errores de autenticacion. Notifica al exterior mediante un
 * callback {@code (username, password) -> ...} segun el boton pulsado.</p>
 */
public class LoginView extends JPanel {

    private final JTextField userField = new JTextField(18);
    private final JPasswordField passField = new JPasswordField(18);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton loginBtn = new JButton("Iniciar sesion");
    private final JButton registerBtn = new JButton("Registrarse");

    /**
     * @param onLogin    callback (usuario, contrasena) al pulsar "Iniciar sesion"
     * @param onRegister callback (usuario, contrasena) al pulsar "Registrarse"
     */
    public LoginView(BiConsumer<String, String> onLogin, BiConsumer<String, String> onRegister) {
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(40, 60, 40, 60));

        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 6, 6, 6);
        g.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Dog Messenger");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        add(title, g);

        g.gridwidth = 1;
        g.gridx = 0; g.gridy = 1; add(new JLabel("Usuario:"), g);
        g.gridx = 1; add(userField, g);

        g.gridx = 0; g.gridy = 2; add(new JLabel("Contrasena:"), g);
        g.gridx = 1; add(passField, g);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttons.add(loginBtn);
        buttons.add(registerBtn);
        g.gridx = 0; g.gridy = 3; g.gridwidth = 2;
        add(buttons, g);

        statusLabel.setForeground(new Color(0xB00020));
        g.gridy = 4;
        add(statusLabel, g);

        loginBtn.addActionListener(e -> fire(onLogin));
        registerBtn.addActionListener(e -> fire(onRegister));
        // Enter en la contrasena = login
        passField.addActionListener(e -> fire(onLogin));
    }

    private void fire(BiConsumer<String, String> cb) {
        String u = userField.getText().trim();
        String p = new String(passField.getPassword());
        if (u.isEmpty() || p.isEmpty()) {
            showError("Usuario y contrasena son obligatorios.");
            return;
        }
        setBusy(true);
        cb.accept(u, p);
    }

    /** Muestra un mensaje de error de autenticacion. */
    public void showError(String message) {
        setBusy(false);
        statusLabel.setForeground(new Color(0xB00020));
        statusLabel.setText(message);
    }

    public void showInfo(String message) {
        statusLabel.setForeground(new Color(0x2E7D32));
        statusLabel.setText(message);
    }

    public void setBusy(boolean busy) {
        loginBtn.setEnabled(!busy);
        registerBtn.setEnabled(!busy);
    }
}