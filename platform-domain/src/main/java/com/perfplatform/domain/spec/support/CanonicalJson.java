package com.perfplatform.domain.spec.support;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Emits JSON whose bytes are decided entirely by the caller.
 *
 * <p>Exists because several things in this platform are identified by the digest of their JSON
 * form — specification versions, execution plans — and a digest is only an identity if the same
 * input always produces the same bytes. A serialization library cannot promise that across
 * versions: field ordering, number formatting and escaping are all implementation details it is
 * free to change, and a library upgrade that changed any of them would silently invalidate every
 * stored digest.
 *
 * <p>Deliberately minimal and deliberately not a general serializer. It writes what it is told, in
 * the order it is told, with no reflection, no field discovery and no configuration. Structural
 * correctness (matched braces and brackets, commas only between members) is enforced by the writer
 * rather than left to the caller, because that is the part hand-written JSON gets wrong.
 *
 * <p>Numbers are written as {@code long} or as already-formatted text. There is no {@code double}
 * accessor: floating-point rendering is locale- and platform-sensitive, and a digest input must not
 * depend on either.
 */
public final class CanonicalJson {

    private enum Scope { OBJECT, ARRAY }

    private final StringBuilder out = new StringBuilder(256);
    private final Deque<Scope> scopes = new ArrayDeque<>();
    private boolean needsComma;

    public CanonicalJson startObject() {
        prefix();
        out.append('{');
        scopes.push(Scope.OBJECT);
        needsComma = false;
        return this;
    }

    public CanonicalJson endObject() {
        require(Scope.OBJECT, '}');
        return this;
    }

    public CanonicalJson startArray(String key) {
        member(key);
        out.append('[');
        scopes.push(Scope.ARRAY);
        needsComma = false;
        return this;
    }

    public CanonicalJson endArray() {
        require(Scope.ARRAY, ']');
        return this;
    }

    /** Starts an object as an array element. */
    public CanonicalJson startElement() {
        prefix();
        out.append('{');
        scopes.push(Scope.OBJECT);
        needsComma = false;
        return this;
    }

    public CanonicalJson field(String key, String value) {
        member(key);
        writeString(value == null ? "" : value);
        return this;
    }

    public CanonicalJson field(String key, long value) {
        member(key);
        out.append(value);
        return this;
    }

    public CanonicalJson field(String key, boolean value) {
        member(key);
        out.append(value);
        return this;
    }

    /** The finished document. */
    public String build() {
        if (!scopes.isEmpty()) {
            throw new IllegalStateException(
                    "Unbalanced JSON: " + scopes.size() + " structure(s) were not closed");
        }
        return out.toString();
    }

    private void member(String key) {
        if (scopes.peek() != Scope.OBJECT) {
            throw new IllegalStateException("A named field can only appear inside an object");
        }
        prefix();
        writeString(key);
        out.append(':');
        // needsComma stays true, so the *next* member is separated from this one. Clearing it here
        // suppressed the separator for every field after the first, producing documents like
        // {"a":"1""b":"2"} — accepted by nothing. The structures that legitimately need it cleared,
        // startArray and startObject, clear it themselves after pushing their scope, because it is
        // the first member *inside* the new structure that must not be preceded by a comma.
    }

    private void prefix() {
        if (needsComma) {
            out.append(',');
        }
        needsComma = true;
    }

    private void require(Scope expected, char close) {
        Scope actual = scopes.poll();
        if (actual != expected) {
            throw new IllegalStateException(
                    "Cannot close a " + expected + ": the open structure is " + actual);
        }
        out.append(close);
        needsComma = true;
    }

    /**
     * JSON string escaping, including the control characters.
     *
     * <p>A value containing a quote would otherwise emit a document that is not JSON, and one
     * containing a newline would produce something two parsers can disagree about — either of which
     * turns a digest mismatch into a mystery at the point results come back from a runner.
     */
    private void writeString(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
