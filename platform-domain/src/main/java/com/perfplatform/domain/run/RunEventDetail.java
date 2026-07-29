package com.perfplatform.domain.run;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and writes {@link RunEvent#detail()} as a flat JSON object.
 *
 * <h2>Why this is hand-written</h2>
 * The domain has no serialization library and does not acquire one (CLAUDE.md §9): a domain shaped
 * by a wire format is a domain that changes when the wire format does. The existing canonical-form
 * code makes the same choice for the same reason.
 *
 * <p>What makes hand-writing safe here is how narrow the format is. Event detail is a flat map of
 * string to string by construction — {@link RunEvent} offers no way to attach anything else — so
 * this handles exactly one shape and rejects everything outside it, rather than being a general
 * JSON parser with a general parser's surface area.
 *
 * <p>Keys are emitted in sorted order so the same detail always produces the same text. Operators
 * diff these values, and a map whose iteration order shifted between JVM runs would show phantom
 * changes.
 */
public final class RunEventDetail {

    private static final String EMPTY_OBJECT = "{}";

    private RunEventDetail() {
    }

    /** Renders a detail map as a JSON object. Null or empty produces {@code {}}. */
    public static String toJson(Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) {
            return EMPTY_OBJECT;
        }

        StringBuilder json = new StringBuilder(32 + detail.size() * 24);
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(detail).entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            writeString(json, entry.getKey());
            json.append(':');
            writeString(json, entry.getValue() == null ? "" : entry.getValue());
        }
        return json.append('}').toString();
    }

    /**
     * Parses a flat JSON object back into a detail map.
     *
     * @throws IllegalArgumentException if the text is not a flat object of string to string
     */
    public static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }

        Cursor cursor = new Cursor(json);
        cursor.skipWhitespace();
        cursor.expect('{');
        cursor.skipWhitespace();

        Map<String, String> detail = new LinkedHashMap<>();
        if (cursor.peek() == '}') {
            cursor.next();
            cursor.requireEnd();
            return Map.copyOf(detail);
        }

        while (true) {
            cursor.skipWhitespace();
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            cursor.skipWhitespace();
            detail.put(key, cursor.readString());
            cursor.skipWhitespace();

            char delimiter = cursor.next();
            if (delimiter == '}') {
                cursor.requireEnd();
                return Map.copyOf(detail);
            }
            if (delimiter != ',') {
                throw new IllegalArgumentException(
                        "Expected ',' or '}' in run event detail at position " + cursor.position());
            }
        }
    }

    private static void writeString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    /** Position-tracking reader. Kept private so the format stays closed. */
    private static final class Cursor {

        private final String text;
        private int index;

        Cursor(String text) {
            this.text = text;
        }

        int position() {
            return index;
        }

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        char peek() {
            requireMore();
            return text.charAt(index);
        }

        char next() {
            requireMore();
            return text.charAt(index++);
        }

        void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' in run event detail "
                        + "at position " + (index - 1) + " but found '" + actual + "'");
            }
        }

        void requireEnd() {
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException(
                        "Trailing content in run event detail at position " + index);
            }
        }

        String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                char escaped = next();
                switch (escaped) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> value.append(readUnicode());
                    default -> throw new IllegalArgumentException(
                            "Unsupported escape '\\" + escaped + "' in run event detail at position "
                                    + (index - 1));
                }
            }
        }

        private char readUnicode() {
            if (index + 4 > text.length()) {
                throw new IllegalArgumentException(
                        "Truncated unicode escape in run event detail at position " + index);
            }
            String hex = text.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Malformed unicode escape '\\u" + hex + "' in run event detail");
            }
        }

        private void requireMore() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("Run event detail ended unexpectedly");
            }
        }
    }
}
