package apigw.observability;

/** JSON string literal escaping (RFC 8259) for hand-written JSON lines and payloads. */
public final class JsonText {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonText() {
    }

    /** Appends {@code s} as a JSON string literal, escaping quotes, backslashes, controls and U+2028/2029. */
    public static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append("\\u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
                                .append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
