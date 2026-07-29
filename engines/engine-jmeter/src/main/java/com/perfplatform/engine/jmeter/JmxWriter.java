package com.perfplatform.engine.jmeter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Writes JMeter test plan XML, with the structure enforced rather than trusted.
 *
 * <h2>Why this is not a template</h2>
 * V1 generated JMX from Freemarker, and that produced the worst defect in the baseline: Freemarker
 * applies locale-aware digit grouping to numeric models, so a thread count of 1,000 rendered as
 * {@code 1,000} and JMeter could not parse it. The platform's stated maximum is 1,000 virtual users,
 * so V1 could not execute a test at the scale it was specified for (defect D1).
 *
 * <p>That defect is a property of putting numbers through a text formatter, and it recurs: every new
 * numeric field is one forgotten {@code ?c} away from silently corrupting a plan. Here numbers are
 * appended as numbers and never reach a formatter, so the whole class of defect is unrepresentable
 * rather than patched.
 *
 * <p>The second reason is structural. Every JMeter element must be followed by a sibling
 * {@code <hashTree>} holding its children, and a plan whose nesting does not match is one JMeter
 * mis-parses rather than rejects — the worst possible failure, because it runs and measures
 * something other than what was asked for. Here the pairing is by construction: an element is always
 * emitted with its hashTree, and {@link #build()} refuses to produce a document with anything left
 * open.
 *
 * <p>CLAUDE.md §5 asks that the Freemarker templates be preserved as migration inputs. They are:
 * they still ship, V1 still uses them, and this structure was derived from them. What they are not
 * is the mechanism V2 generates with.
 */
final class JmxWriter {

    private static final String INDENT = "  ";

    private final StringBuilder out = new StringBuilder(4096);

    /** Open element names, innermost first. Depth drives indentation and the balance check. */
    private final Deque<String> open = new ArrayDeque<>();

    JmxWriter() {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.2\">\n")
                .append(INDENT).append("<hashTree>\n");
    }

    /**
     * Opens an element. Close it with {@link #endLeaf()} or {@link #endWithChildren()}.
     *
     * @param tag        the JMeter element name, e.g. {@code ThreadGroup}
     * @param attributes attribute text; values must already be escaped
     */
    JmxWriter element(String tag, String attributes) {
        indent(open.size() + 1);
        out.append('<').append(tag).append(' ').append(attributes).append(">\n");
        open.push(tag);
        return this;
    }

    /** Closes an element that has no children, emitting its empty hashTree. */
    JmxWriter endLeaf() {
        String tag = open.pop();
        indent(open.size() + 1);
        out.append("</").append(tag).append(">\n");
        indent(open.size() + 1);
        out.append("<hashTree/>\n");
        return this;
    }

    /** Closes an element and opens the hashTree holding its children. */
    JmxWriter endWithChildren() {
        String tag = open.pop();
        indent(open.size() + 1);
        out.append("</").append(tag).append(">\n");
        indent(open.size() + 1);
        out.append("<hashTree>\n");
        // Pushed back as a marker so children indent inside it and the balance check still sees it.
        open.push(tag);
        return this;
    }

    /** Closes the hashTree opened by {@link #endWithChildren()}. */
    JmxWriter endChildren() {
        open.pop();
        indent(open.size() + 1);
        out.append("</hashTree>\n");
        return this;
    }

    JmxWriter stringProp(String name, String value) {
        return prop("stringProp", name, escape(value));
    }

    /**
     * A numeric property.
     *
     * <p>{@code long}, appended directly. No formatter is involved, which is what makes defect D1
     * unrepresentable here rather than merely absent today.
     */
    JmxWriter longProp(String name, long value) {
        return prop("stringProp", name, Long.toString(value));
    }

    JmxWriter intProp(String name, int value) {
        return prop("intProp", name, Integer.toString(value));
    }

    JmxWriter boolProp(String name, boolean value) {
        return prop("boolProp", name, Boolean.toString(value));
    }

    /** Pre-formed XML, for the fixed blocks JMeter requires verbatim. */
    JmxWriter raw(String xml) {
        for (String line : xml.split("\n", -1)) {
            if (!line.isBlank()) {
                indent(open.size() + 2);
                out.append(line.strip()).append('\n');
            }
        }
        return this;
    }

    /**
     * The finished document.
     *
     * @throws IllegalStateException if anything was left open. A truncated plan is one JMeter may
     *                               still load and execute incorrectly, so it must never be produced
     */
    String build() {
        if (!open.isEmpty()) {
            throw new IllegalStateException("Unbalanced JMeter plan: " + open.size()
                    + " element(s) were not closed — innermost is " + open.peek());
        }
        return out.append(INDENT).append("</hashTree>\n")
                .append("</jmeterTestPlan>\n")
                .toString();
    }

    private JmxWriter prop(String tag, String name, String value) {
        indent(open.size() + 1);
        out.append('<').append(tag).append(" name=\"").append(name).append("\">")
                .append(value)
                .append("</").append(tag).append(">\n");
        return this;
    }

    private void indent(int depth) {
        out.append(INDENT.repeat(depth));
    }

    /**
     * XML escaping.
     *
     * <p>Applied to every value that came from a specification. A path containing {@code &} would
     * otherwise produce a document JMeter refuses to load, and a header value containing a quote
     * would produce one that loads as something else.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
