import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URI;
import java.net.URISyntaxException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class URLShortenerApp {
    private static final String BASE_URL = "https://sho.rt/";
    private static final char[] BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final Font UI_FONT = new Font("SansSerif", Font.PLAIN, 15);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(URLShortenerApp::createAndShowUi);
    }

    private static void createAndShowUi() {
        applySystemLookAndFeel();

        JFrame frame = new JFrame("URL Shortener");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setPreferredSize(new Dimension(760, 280));

        Color orange = new Color(245, 130, 32);
        Color white = Color.WHITE;

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(white);
        root.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(white);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Paste your long URL");
        title.setForeground(orange);
        title.setFont(UI_FONT.deriveFont(Font.BOLD, 21f));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        form.add(title, gbc);

        JTextField inputField = new JTextField();
        inputField.setFont(UI_FONT);
        inputField.setPreferredSize(new Dimension(620, 40));
        gbc.gridy = 1;
        form.add(inputField, gbc);

        JButton shortenButton = new JButton("Shorten URL");
        shortenButton.setFont(UI_FONT.deriveFont(Font.BOLD));
        shortenButton.setBackground(orange);
        shortenButton.setForeground(white);
        shortenButton.setFocusPainted(false);
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        form.add(shortenButton, gbc);

        JTextField outputField = new JTextField();
        outputField.setFont(UI_FONT);
        outputField.setEditable(false);
        outputField.setBackground(white);
        outputField.setForeground(orange.darker());
        gbc.gridx = 1;
        form.add(outputField, gbc);

        JButton copyButton = new JButton("Copy");
        copyButton.setFont(UI_FONT.deriveFont(Font.BOLD));
        copyButton.setBackground(orange.brighter());
        copyButton.setForeground(Color.BLACK);
        copyButton.setFocusPainted(false);
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        form.add(copyButton, gbc);

        shortenButton.addActionListener(event -> {
            String longUrl = inputField.getText().trim();
            if (longUrl.isEmpty()) {
                showError(frame, "Please enter a URL.");
                return;
            }

            try {
                outputField.setText(shorten(longUrl));
            } catch (IllegalArgumentException ex) {
                showError(frame, ex.getMessage());
            }
        });

        copyButton.addActionListener(event -> {
            String shortUrl = outputField.getText().trim();
            if (shortUrl.isEmpty()) {
                showError(frame, "Generate a short URL before copying.");
                return;
            }

            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(shortUrl), null);
        });

        root.add(form, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    static String shorten(String longUrl) {
        String normalizedUrl = normalizeUrl(longUrl);
        if (!isValidHttpUrl(normalizedUrl)) {
            throw new IllegalArgumentException("Please enter a valid http/https URL.");
        }

        byte[] digest;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digest = md.digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to create short URL.", e);
        }

        long value = 0;
        for (int i = 0; i < 6; i++) {
            value = (value << 8) | (digest[i] & 0xffL);
        }

        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            token.append(BASE62[(int) (value % BASE62.length)]);
            value /= BASE62.length;
        }

        return BASE_URL + token.reverse();
    }

    private static String normalizeUrl(String value) {
        String normalized = value.trim();
        if (!normalized.matches("(?i)^https?://.*")) {
            normalized = "https://" + normalized;
        }
        return normalized;
    }

    private static boolean isValidHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static void showError(JFrame frame, String message) {
        JOptionPane.showMessageDialog(frame, message, "Invalid URL", JOptionPane.ERROR_MESSAGE);
    }

    private static void applySystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // If look and feel fails, continue with default Swing style.
        }
    }
}
