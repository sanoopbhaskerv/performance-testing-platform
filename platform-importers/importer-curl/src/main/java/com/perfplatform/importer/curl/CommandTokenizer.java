package com.perfplatform.importer.curl;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a cURL command line into arguments.
 *
 * <p>A deliberately small, deliberately dumb shell-quoting parser. It exists because the alternative
 * — handing the string to a shell — would execute arbitrary code that arrived in an HTTP request.
 * CLAUDE.md §23 requires cURL to be parsed "without shell execution", and this is what that means in
 * practice.
 *
 * <p>It therefore implements only the quoting a real pasted command uses: single quotes, double
 * quotes, backslash escapes, and line continuations. Everything else a shell would do —
 * substitution, globbing, pipes, redirection, subshells — is <b>not interpreted</b>. A
 * {@code $(rm -rf /)} in the input is a literal seven-character argument here, which is exactly the
 * point: characters that would be dangerous to a shell are inert to a parser that has no shell.
 */
final class CommandTokenizer {

    private CommandTokenizer() {
    }

    /**
     * Splits into arguments.
     *
     * <p>Never throws. An unterminated quote yields whatever had accumulated rather than an error:
     * the caller has better context for saying "this does not look like a cURL command", and a
     * tokenizer that threw would turn a paste mistake into a stack trace.
     */
    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return tokens;
        }

        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean started = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // A backslash escapes the next character outside single quotes, where a shell treats
            // everything literally. A trailing backslash before a newline is a line continuation,
            // which is how dev-tools "copy as cURL" formats every command it produces.
            if (c == '\\' && !inSingle && i + 1 < command.length()) {
                char next = command.charAt(i + 1);
                if (next == '\n' || next == '\r') {
                    i++;
                    if (next == '\r' && i + 1 < command.length() && command.charAt(i + 1) == '\n') {
                        i++;
                    }
                    continue;
                }
                current.append(next);
                started = true;
                i++;
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                started = true;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                started = true;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }

            current.append(c);
            started = true;
        }

        if (started) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
