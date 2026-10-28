public class SwingChatCheck {
    public static void main(String[] args) {
        check("plain text", "\"hello\"", SwingChat.jsonQuote("hello"));
        check("quotes and backslashes", "\"say \\\"hi\\\" C:\\\\temp\"",
                SwingChat.jsonQuote("say \"hi\" C:\\temp"));
        check("newline, carriage return, and tab", "\"line1\\nline2\\r\\tend\"",
                SwingChat.jsonQuote("line1\nline2\r\tend"));
        check("control escapes", "\"\\u0001\\u001F\"", SwingChat.jsonQuote("\u0001\u001F"));
        check("empty string", "\"\"", SwingChat.jsonQuote(""));
        String ollama = "{\"message\":{\"content\":\"Hi there!\\nHow can I help?\"}}";
        check("Ollama content", "Hi there!\nHow can I help?", SwingChat.extractText(ollama));
        check("structured field", "Paris", SwingChat.extractField("{\"answer\":\"Paris\"}", "answer"));
        check("structured Ollama answer", "Paris",
                SwingChat.extractAnswer("{\"message\":{\"content\":\"{\\\"answer\\\":\\\"Paris\\\"}\"}}"));
        check("Ollama generate response", "5",
                SwingChat.extractGeneratedResponse("{\"response\":\"5\",\"done\":true}"));
        check("missing Ollama generate response", "The model did not return a valid answer",
                SwingChat.extractGeneratedResponse("{\"done\":true}"));
        check("simple arithmetic", "5", SwingChat.calculateSimpleArithmetic("2+3"));
        check("natural language arithmetic", "2.5", SwingChat.calculateSimpleArithmetic("What is 10 / 4?"));
        check("division by zero", "Cannot divide by zero.", SwingChat.calculateSimpleArithmetic("1 / 0"));
        check("non-arithmetic prompt", null, SwingChat.calculateSimpleArithmetic("What is the weather?"));
        check("invalid structured JSON", "The model did not return a valid answer",
                SwingChat.extractAnswer("{\"message\":{\"content\":\"not JSON\"}}"));
        check("missing structured field", "The model did not return a valid answer",
                SwingChat.extractAnswer("{\"message\":{\"content\":\"{\\\"other\\\":\\\"value\\\"}\"}}"));
    }

    private static void check(String name, String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
        System.out.println(name + ": passed");
    }
}
