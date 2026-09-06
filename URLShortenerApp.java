import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class URLShortenerApp {
    private static final char[] BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final Font UI_FONT = new Font("SansSerif", Font.PLAIN, 15);
    private static final Map<String, String> SHORT_URLS = new ConcurrentHashMap<>();
    private static volatile HttpServer redirectServer;
    private static volatile String redirectBaseUrl;

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

        JButton shortenButton = new JButton("Shorten Link");
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

        ensureRedirectServerStarted();
        String token = createOrReuseToken(normalizedUrl);
        return redirectBaseUrl + token;
    }

    private static String createOrReuseToken(String normalizedUrl) {
        byte[] digest;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digest = md.digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to create short URL.", e);
        }

        String base62Digest = encodeBase62(digest);
        for (int length = 7; length <= Math.min(12, base62Digest.length()); length++) {
            String token = base62Digest.substring(0, length);
            String existing = SHORT_URLS.putIfAbsent(token, normalizedUrl);
            if (existing == null || existing.equals(normalizedUrl)) {
                return token;
            }
        }

        throw new IllegalStateException("Unable to create unique short URL.");
    }

    private static synchronized void ensureRedirectServerStarted() {
        if (redirectServer != null) {
            return;
        }

        try {
            redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            redirectServer.createContext("/", URLShortenerApp::handleRedirect);
            redirectServer.start();
            redirectBaseUrl = "http://127.0.0.1:" + redirectServer.getAddress().getPort() + "/";
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start redirect server.", e);
        }
    }

    private static void handleRedirect(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String token = path != null && path.length() > 1 ? path.substring(1) : "";
        String destination = SHORT_URLS.get(token);

        if (destination == null) {
            byte[] message = "Short URL not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, message.length);
            exchange.getResponseBody().write(message);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Location", destination);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static String encodeBase62(byte[] input) {
        BigInteger value = new BigInteger(1, input);
        if (value.equals(BigInteger.ZERO)) {
            return "0";
        }

        BigInteger base = BigInteger.valueOf(BASE62.length);
        StringBuilder encoded = new StringBuilder();
        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] parts = value.divideAndRemainder(base);
            encoded.append(BASE62[parts[1].intValue()]);
            value = parts[0];
        }

        return encoded.reverse().toString();
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
