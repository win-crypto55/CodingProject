import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

public class SwingChat {
    private static final String MODEL = "llama3.2:1b";
    private static final String INVALID_ANSWER = "The model did not return a valid answer";
    private static final Color BACKGROUND = new Color(245, 247, 250);
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(17, 24, 39);
    private static final Color MUTED_TEXT = new Color(107, 114, 128);
    private static final Color ACCENT = new Color(37, 99, 235);
    private static final Color BORDER = new Color(218, 225, 236);
    private static final Color USER_BUBBLE = new Color(219, 234, 254);
    private static final Color BOT_BUBBLE = new Color(239, 246, 255);
    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final Pattern SIMPLE_ARITHMETIC = Pattern.compile(
            "^(?:what is\\s+)?([+-]?\\d+(?:\\.\\d+)?)\\s*([+*/-])\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*\\??$",
            Pattern.CASE_INSENSITIVE);

    interface LlmClient {
        String complete(String prompt) throws Exception;
    }

    private static final class HttpStatusException extends IOException {
        HttpStatusException(int statusCode, String responseBody) {
            super("Ollama returned HTTP " + statusCode
                    + (getErrorDetail(responseBody).isEmpty() ? "" : ": " + getErrorDetail(responseBody))
                    + ". If the model is not downloaded, run: ollama pull " + MODEL);
        }

        private static String getErrorDetail(String responseBody) {
            try {
                return extractField(responseBody, "error").replaceAll("\\s+", " ").trim();
            } catch (IllegalArgumentException exception) {
                return responseBody.replaceAll("\\s+", " ").trim();
            }
        }
    }

    static final class FakeClient implements LlmClient {
        @Override
        public String complete(String prompt) throws InterruptedException {
            Thread.sleep(400);
            return "Echo: " + prompt;
        }
    }

    static final class OllamaClient implements LlmClient {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        @Override
        public String complete(String prompt) throws Exception {
            String body = "{"
                    + "\"model\":" + jsonQuote(MODEL)
                    + ",\"prompt\":" + jsonQuote(prompt)
                    + ",\"stream\":false"
                    + ",\"keep_alive\":\"10m\""
                    + ",\"options\":{\"num_ctx\":1024,\"num_predict\":128,\"temperature\":0.2}"
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .timeout(Duration.ofSeconds(300))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new HttpStatusException(response.statusCode(), response.body());
            }
            return extractGeneratedResponse(response.body());
        }
    }

    static String extractGeneratedResponse(String ollamaResponse) {
        try {
            return extractField(ollamaResponse, "response");
        } catch (IllegalArgumentException exception) {
            return INVALID_ANSWER;
        }
    }

    static String extractAnswer(String ollamaResponse) {
        try {
            return extractField(extractText(ollamaResponse), "answer");
        } catch (IllegalArgumentException exception) {
            return INVALID_ANSWER;
        }
    }

    static String calculateSimpleArithmetic(String prompt) {
        Matcher matcher = SIMPLE_ARITHMETIC.matcher(prompt.trim());
        if (!matcher.matches()) {
            return null;
        }

        BigDecimal left = new BigDecimal(matcher.group(1));
        BigDecimal right = new BigDecimal(matcher.group(3));
        BigDecimal result;
        switch (matcher.group(2)) {
            case "+":
                result = left.add(right);
                break;
            case "-":
                result = left.subtract(right);
                break;
            case "*":
                result = left.multiply(right);
                break;
            case "/":
                if (right.signum() == 0) {
                    return "Cannot divide by zero.";
                }
                result = left.divide(right, MathContext.DECIMAL64);
                break;
            default:
                throw new IllegalStateException("Unexpected arithmetic operator");
        }
        return result.stripTrailingZeros().toPlainString();
    }

    static String jsonQuote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04X", (int) character));
                    } else {
                        result.append(character);
                    }
                    break;
            }
        }
        return result.append('"').toString();
    }

    static String extractText(String json) {
        Object parsed = new JsonParser(json).parse();
        if (parsed instanceof Map<?, ?> root) {
            Object message = root.get("message");
            if (message instanceof Map<?, ?> messageObject) {
                Object content = messageObject.get("content");
                if (content instanceof String text) {
                    return text;
                }
            }
        }
        throw new IllegalArgumentException("Missing message.content string");
    }

    static String extractField(String json, String field) {
        Object parsed = new JsonParser(json).parse();
        if (parsed instanceof Map<?, ?> object) {
            Object value = object.get(field);
            if (value instanceof String text) {
                return text;
            }
        }
        throw new IllegalArgumentException("Missing string field: " + field);
    }

    private static final class JsonParser {
        private final String input;
        private int position;

        JsonParser(String input) {
            this.input = input;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (position != input.length()) {
                throw error("Unexpected trailing data");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (position >= input.length()) {
                throw error("Expected a value");
            }
            char current = input.charAt(position);
            switch (current) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    return parseLiteral("true", Boolean.TRUE);
                case 'f':
                    return parseLiteral("false", Boolean.FALSE);
                case 'n':
                    return parseLiteral("null", null);
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (take('}')) {
                return object;
            }
            while (true) {
                skipWhitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw error("Expected an object key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (take('}')) {
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            List<Object> array = new ArrayList<>();
            position++;
            skipWhitespace();
            if (take(']')) {
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (take(']')) {
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < input.length()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (position >= input.length()) {
                        throw error("Trailing escape");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"':
                            result.append('"');
                            break;
                        case '\\':
                            result.append('\\');
                            break;
                        case '/':
                            result.append('/');
                            break;
                        case 'b':
                            result.append('\b');
                            break;
                        case 'f':
                            result.append('\f');
                            break;
                        case 'n':
                            result.append('\n');
                            break;
                        case 'r':
                            result.append('\r');
                            break;
                        case 't':
                            result.append('\t');
                            break;
                        case 'u':
                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit(input.charAt(position++), 16);
                                if (digit < 0) {
                                    throw error("Invalid unicode escape");
                                }
                                value = value * 16 + digit;
                            }
                            result.append((char) value);
                            break;
                        default:
                            throw error("Invalid escape sequence: \\" + escaped);
                    }
                } else {
                    result.append(character);
                }
            }
            throw error("Unterminated string");
        }

        private Object parseNumber() {
            int start = position;
            take('-');
            if (take('0')) {
                if (position < input.length() && Character.isDigit(input.charAt(position))) {
                    throw error("Leading zero in number");
                }
            } else {
                requireDigits();
            }
            if (take('.')) {
                requireDigits();
            }
            if (take('e') || take('E')) {
                if (!take('+')) {
                    take('-');
                }
                requireDigits();
            }
            if (position == start) {
                throw error("Invalid value");
            }
            return input.substring(start, position);
        }

        private void requireDigits() {
            int start = position;
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("Expected a digit");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, position)) {
                throw error("Invalid value");
            }
            position += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (position < input.length()) {
                char character = input.charAt(position);
                if (character == ' ' || character == '\n' || character == '\r' || character == '\t') {
                    position++;
                } else {
                    return;
                }
            }
        }

        private boolean take(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }

    private static final class ChatMessage {
        private final String role;
        private final String text;

        private ChatMessage(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private final JFrame frame = new JFrame("SwingChat");
    private final JTextField promptField = new JTextField();
    private final JPanel chatPanel = new JPanel();
    private final JScrollPane chatScrollPane = new JScrollPane(chatPanel);
    private final JButton sendButton = new JButton("Send");
    private final JLabel statusLabel = new JLabel("Ready");
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private final LlmClient client = new OllamaClient();

    private SwingChat() {
        Font bodyFont = new Font("SansSerif", Font.PLAIN, 14);
        Font sectionFont = bodyFont.deriveFont(Font.BOLD, 15f);

        promptField.setFont(bodyFont);
        promptField.setForeground(TEXT);
        promptField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(10, 12, 10, 12)));
        promptField.setMargin(new Insets(6, 10, 6, 10));

        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setBackground(BACKGROUND);
        chatPanel.setBorder(new EmptyBorder(12, 12, 12, 12));
        chatScrollPane.setBorder(BorderFactory.createLineBorder(BORDER));
        chatScrollPane.getViewport().setBackground(BACKGROUND);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JLabel promptLabel = new JLabel("Your message");
        promptLabel.setFont(sectionFont);
        promptLabel.setForeground(TEXT);

        JLabel historyLabel = new JLabel("Chat history");
        historyLabel.setFont(sectionFont);
        historyLabel.setForeground(TEXT);

        sendButton.setFont(bodyFont.deriveFont(Font.BOLD));
        sendButton.setForeground(Color.WHITE);
        sendButton.setBackground(ACCENT);
        sendButton.setFocusPainted(false);
        sendButton.setBorder(new EmptyBorder(10, 20, 10, 20));
        sendButton.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

        JPanel inputPanel = new JPanel(new BorderLayout(12, 0));
        inputPanel.setBackground(SURFACE);
        inputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(16, 18, 16, 18)));
        inputPanel.add(promptLabel, BorderLayout.NORTH);
        inputPanel.add(promptField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        JPanel historyPanel = new JPanel(new BorderLayout(0, 8));
        historyPanel.setBackground(SURFACE);
        historyPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(16, 18, 16, 18)));
        historyPanel.add(historyLabel, BorderLayout.NORTH);
        historyPanel.add(chatScrollPane, BorderLayout.CENTER);
        historyPanel.setPreferredSize(new Dimension(0, 330));

        JLabel titleLabel = new JLabel("SwingChat");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 27));
        titleLabel.setForeground(TEXT);

        JLabel subtitleLabel = new JLabel("Ask a question. Read the past replies. Keep the conversation moving.");
        subtitleLabel.setFont(bodyFont);
        subtitleLabel.setForeground(MUTED_TEXT);

        JPanel heading = new JPanel(new BorderLayout(0, 4));
        heading.setBackground(BACKGROUND);
        heading.setBorder(new EmptyBorder(0, 0, 18, 0));
        heading.add(titleLabel, BorderLayout.NORTH);
        heading.add(subtitleLabel, BorderLayout.CENTER);

        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setBackground(BACKGROUND);
        body.add(inputPanel, BorderLayout.NORTH);
        body.add(historyPanel, BorderLayout.CENTER);

        statusLabel.setFont(bodyFont.deriveFont(12f));
        statusLabel.setForeground(MUTED_TEXT);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(BACKGROUND);
        footer.setBorder(new EmptyBorder(10, 0, 0, 0));
        footer.add(statusLabel, BorderLayout.WEST);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(new EmptyBorder(24, 24, 18, 24));
        content.add(heading, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);

        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(740, 620));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);

        promptField.addActionListener(this::sendPrompt);
        sendButton.addActionListener(this::sendPrompt);

        addBotGreeting();
    }

    private void addBotGreeting() {
        addMessage("Bot", "Hi! Ask me something and I’ll reply in the chat history below.");
    }

    private void sendPrompt(ActionEvent event) {
        String prompt = promptField.getText().trim();
        if (prompt.isEmpty()) {
            statusLabel.setText("Error");
            return;
        }

        addMessage("You", prompt);
        promptField.setText("");
        String arithmeticAnswer = calculateSimpleArithmetic(prompt);
        if (arithmeticAnswer != null) {
            addMessage("Bot", arithmeticAnswer);
            statusLabel.setText("Ready");
            promptField.requestFocusInWindow();
            return;
        }

        promptField.setEnabled(false);
        sendButton.setEnabled(false);
        statusLabel.setText("Thinking…");
        String conversationPrompt = buildConversationPrompt();

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return client.complete(conversationPrompt);
            }

            @Override
            protected void done() {
                try {
                    String response = get();
                    addMessage("Bot", response);
                    statusLabel.setText("Ready");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    addMessage("Bot", "The request was interrupted.");
                    statusLabel.setText("Error");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (hasConnectionRefusal(cause)) {
                        addMessage("Bot", "Could not connect to Ollama. Please start Ollama and try again.");
                    } else if (cause instanceof HttpStatusException) {
                        addMessage("Bot", cause.getMessage());
                    } else if (cause instanceof HttpTimeoutException) {
                        addMessage("Bot", "Ollama took too long to answer. Try a shorter question or check that the model is loaded.");
                    } else {
                        String detail = cause.getMessage();
                        if (detail == null || detail.isBlank()) {
                            detail = cause.getClass().getSimpleName();
                        }
                        addMessage("Bot", "Ollama request failed: " + detail);
                    }
                    statusLabel.setText("Error");
                } finally {
                    promptField.setEnabled(true);
                    sendButton.setEnabled(true);
                    promptField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    private String buildConversationPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Answer the latest user question clearly and concisely. Keep replies brief, helpful, and natural.\n\n");

        int startIndex = Math.max(0, chatHistory.size() - MAX_HISTORY_MESSAGES);
        for (int i = startIndex; i < chatHistory.size(); i++) {
            ChatMessage message = chatHistory.get(i);
            prompt.append(message.role).append(": ").append(message.text).append("\n");
        }

        return prompt.toString();
    }

    private void addMessage(String role, String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) {
            return;
        }

        ChatMessage chatMessage = new ChatMessage(role, cleaned);
        chatHistory.add(chatMessage);

        JPanel entry = new JPanel(new BorderLayout(0, 6));
        entry.setOpaque(false);
        entry.setBorder(new EmptyBorder(0, 0, 8, 0));

        JLabel sender = new JLabel(role);
        sender.setFont(new Font("SansSerif", Font.BOLD, 11));
        sender.setForeground(role.equals("Bot") ? new Color(79, 70, 229) : new Color(30, 64, 175));

        JLabel bubbleLabel = new JLabel(htmlForMessage(cleaned));
        bubbleLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        bubbleLabel.setForeground(TEXT);
        bubbleLabel.setVerticalAlignment(JLabel.TOP);

        JPanel bubble = new JPanel(new BorderLayout(0, 4));
        bubble.setOpaque(true);
        bubble.setBackground(role.equals("Bot") ? BOT_BUBBLE : USER_BUBBLE);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 214, 235), 1),
                new EmptyBorder(10, 12, 10, 12)));
        bubble.add(sender, BorderLayout.NORTH);
        bubble.add(bubbleLabel, BorderLayout.CENTER);

        if (role.equals("You")) {
            entry.add(bubble, BorderLayout.EAST);
        } else {
            entry.add(bubble, BorderLayout.WEST);
        }

        chatPanel.add(entry);
        chatPanel.revalidate();
        chatPanel.repaint();
        SwingUtilities.invokeLater(() -> {
            chatScrollPane.getVerticalScrollBar().setValue(chatScrollPane.getVerticalScrollBar().getMaximum());
        });
    }

    private static String htmlForMessage(String text) {
        String html = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><div style='width: 420px;'>" + html + "</div></html>";
    }

    private static boolean hasConnectionRefusal(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SwingChat().frame.setVisible(true));
    }
}
