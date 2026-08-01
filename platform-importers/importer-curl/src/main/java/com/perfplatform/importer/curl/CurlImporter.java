package com.perfplatform.importer.curl;

import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.validation.SecretValueDetector;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a cURL command into a draft scenario.
 *
 * <p>The lowest-friction way onto the platform. Everyone has a cURL command — usually copied out of
 * browser dev tools, where "Copy as cURL" produces exactly the request a real user made — and it
 * already carries the method, path, headers and body a test needs.
 *
 * <h2>Nothing is executed</h2>
 * The command is tokenized and read as arguments. It is never handed to a shell, so a
 * {@code $(...)} or a {@code ; rm -rf} in the input is inert text rather than a command
 * (CLAUDE.md §23).
 *
 * <h2>Credentials are stripped, and their absence is reported</h2>
 * A pasted cURL command is one of the most reliable ways to leak a live token: it comes from a real
 * session, so its {@code Authorization} header and cookies are real. They are dropped, and a finding
 * says so — a consumer who is told "authentication was removed, declare it as a secret reference"
 * will do that, where one who is told nothing discovers it as a wall of 401s at run time.
 *
 * <h2>The host is dropped too</h2>
 * A source names a host; where a run's traffic may go is resolved from the team's registered targets.
 * Honouring the pasted host would be a way around the target policy, so it is reported and discarded.
 */
public final class CurlImporter implements SourceImporter {

    /** Flags that take a value we care about. */
    private static final Set<String> HEADER_FLAGS = Set.of("-H", "--header");
    private static final Set<String> METHOD_FLAGS = Set.of("-X", "--request");
    private static final Set<String> DATA_FLAGS = Set.of(
            "-d", "--data", "--data-raw", "--data-binary", "--data-ascii");
    private static final Set<String> URL_FLAGS = Set.of("--url");

    /**
     * Flags carrying credentials. Recognised so their presence can be <em>reported</em> — the value
     * is consumed and discarded, never read into the draft.
     */
    private static final Set<String> CREDENTIAL_FLAGS = Set.of(
            "-u", "--user", "--oauth2-bearer", "-E", "--cert", "--key", "--proxy-user");

    /** Flags carrying a cookie, which is a credential in every case that matters. */
    private static final Set<String> COOKIE_FLAGS = Set.of("-b", "--cookie", "-c", "--cookie-jar");

    /**
     * Value-taking flags that do not affect the request the platform will send.
     *
     * <p>Listed so their <em>value</em> is skipped rather than mistaken for a URL — otherwise
     * {@code --retry 3} would import a scenario addressing {@code /3}.
     */
    private static final Set<String> IGNORED_VALUE_FLAGS = Set.of(
            "--connect-timeout", "-m", "--max-time", "--retry", "--retry-delay", "--retry-max-time",
            "-A", "--user-agent", "-e", "--referer", "-o", "--output", "-w", "--write-out",
            "--resolve", "--interface", "--limit-rate", "--proxy", "-x", "--cacert", "--capath");

    @Override
    public SourceType sourceType() {
        return SourceType.CURL;
    }

    @Override
    public ImportResult importFrom(String document) {
        List<ValidationFinding> findings = new ArrayList<>();
        List<String> tokens = CommandTokenizer.tokenize(document);

        if (tokens.isEmpty()) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.curl.empty", "/", "No command was provided.",
                    "Paste a cURL command, for example: curl -X POST https://host/api/orders "
                            + "-H 'Content-Type: application/json' -d '{}'")));
        }

        if (!"curl".equalsIgnoreCase(tokens.get(0))) {
            return ImportResult.nothing(ValidationResult.of(ValidationFinding.error(
                    "import.curl.notACurlCommand", "/",
                    "This does not look like a cURL command: it does not begin with 'curl'.",
                    "Paste the command exactly as your browser's 'Copy as cURL' produced it.")));
        }

        Parsed parsed = parse(tokens, findings);

        if (parsed.url == null) {
            findings.add(ValidationFinding.error("import.curl.url.missing", "/",
                    "The command names no URL, so there is nothing to call.", null));
            return ImportResult.nothing(ValidationResult.of(findings));
        }

        return buildScenario(parsed, findings);
    }

    /** Everything read out of the command, before it is judged. */
    private static final class Parsed {
        private String url;
        private String method;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final StringBuilder body = new StringBuilder();
        private boolean sawCredential;
        private boolean sawCookie;
    }

    private Parsed parse(List<String> tokens, List<ValidationFinding> findings) {
        Parsed parsed = new Parsed();

        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (METHOD_FLAGS.contains(token) && i + 1 < tokens.size()) {
                parsed.method = tokens.get(++i).toUpperCase(Locale.ROOT);
            } else if (HEADER_FLAGS.contains(token) && i + 1 < tokens.size()) {
                readHeader(tokens.get(++i), parsed, findings);
            } else if (DATA_FLAGS.contains(token) && i + 1 < tokens.size()) {
                // Repeated -d flags are concatenated with '&', which is what cURL itself does.
                if (parsed.body.length() > 0) {
                    parsed.body.append('&');
                }
                parsed.body.append(tokens.get(++i));
            } else if (URL_FLAGS.contains(token) && i + 1 < tokens.size()) {
                parsed.url = tokens.get(++i);
            } else if (CREDENTIAL_FLAGS.contains(token)) {
                // Consumed and discarded. The value is never read into the draft, and the finding
                // never quotes it.
                parsed.sawCredential = true;
                i++;
            } else if (COOKIE_FLAGS.contains(token)) {
                parsed.sawCookie = true;
                i++;
            } else if (IGNORED_VALUE_FLAGS.contains(token)) {
                i++;
            } else if (token.startsWith("-")) {
                // A valueless flag such as -k, -L, --compressed. Ignored: they configure the client,
                // and the platform decides how its own runner behaves.
                continue;
            } else if (parsed.url == null) {
                parsed.url = token;
            }
        }

        return parsed;
    }

    /**
     * Reads one {@code -H 'Name: value'} header, dropping anything that carries a credential.
     *
     * <p>Both checks from {@link SecretValueDetector} apply, and for the same reason they apply to an
     * authored specification: an imported header lands in a document that is stored, rendered and
     * committed to Git. The finding names the header and never its value.
     */
    private static void readHeader(String header, Parsed parsed, List<ValidationFinding> findings) {
        int separator = header.indexOf(':');
        if (separator <= 0) {
            findings.add(ValidationFinding.warning("import.curl.header.malformed", "/headers",
                    "Ignored a header that is not in 'Name: value' form.", null));
            return;
        }

        String name = header.substring(0, separator).trim();
        String value = header.substring(separator + 1).trim();

        if (SecretValueDetector.isCredentialHeader(name)) {
            parsed.sawCredential = true;
            return;
        }
        if (SecretValueDetector.looksLikeCredential(value)) {
            parsed.sawCredential = true;
            findings.add(ValidationFinding.warning("import.curl.header.credentialValue",
                    "/headers/" + name,
                    "Header '" + name + "' looked like a credential and was not imported.",
                    "Declare it under spec.authentication as a secret reference."));
            return;
        }

        // Sent by the client, not chosen by the author: importing them would pin the test to
        // whatever the browser negotiated on the day the command was copied.
        if (name.equalsIgnoreCase("content-length") || name.equalsIgnoreCase("host")
                || name.equalsIgnoreCase("connection")) {
            return;
        }

        parsed.headers.put(name, value);
    }

    private ImportResult buildScenario(Parsed parsed, List<ValidationFinding> findings) {
        URI uri;
        try {
            uri = new URI(parsed.url.trim());
        } catch (URISyntaxException e) {
            findings.add(ValidationFinding.error("import.curl.url.invalid", "/",
                    "The URL in the command could not be read.", null));
            return ImportResult.nothing(ValidationResult.of(findings));
        }

        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();

        if (uri.getHost() != null) {
            // Reported rather than honoured. Where a run's traffic goes is resolved from the team's
            // registered targets for its application; a specification that could name its own host
            // would make the target policy advisory.
            findings.add(ValidationFinding.info("import.curl.host.dropped", "/",
                    "The host '" + uri.getHost() + "' was not imported; only the path was. A run's "
                            + "traffic goes to the target registered for its application, so check "
                            + "that this test's metadata names the application you intend."));
        }

        Map<String, String> query = parseQuery(uri.getRawQuery(), findings);

        String body = parsed.body.toString();
        // cURL's own rule: -d implies POST unless a method was stated.
        String method = parsed.method != null ? parsed.method : (body.isBlank() ? "GET" : "POST");

        if (parsed.sawCredential) {
            findings.add(ValidationFinding.warning("import.curl.credentials.stripped", "/",
                    "Authentication was removed from this command and not imported.",
                    "Declare it under spec.authentication as a secret reference. The runner "
                            + "resolves it at execution time, so it never enters the specification."));
        }
        if (parsed.sawCookie) {
            findings.add(ValidationFinding.warning("import.curl.cookies.stripped", "/",
                    "Cookies were removed from this command and not imported.",
                    "A copied session cookie is a live credential and expires besides. Model "
                            + "sign-in as a step, or declare the credential under "
                            + "spec.authentication."));
        }
        if (!body.isBlank() && SecretValueDetector.looksLikeCredential(body)) {
            findings.add(ValidationFinding.warning("import.curl.body.credential", "/body",
                    "The request body looked like a credential and was not imported.", null));
            body = "";
        }
        if (!body.isBlank()) {
            findings.add(ValidationFinding.info("import.curl.body.static", "/body",
                    "Every virtual user will send this same request body. For a test that writes "
                            + "data, vary it with a dataset — otherwise the run measures the "
                            + "target's behaviour on one repeated payload."));
        }

        ScenarioSpec scenario = new ScenarioSpec(
                nameFor(path),
                100,
                null,
                method,
                path,
                parsed.headers,
                query,
                body.isBlank() ? null : new ScenarioSpec.RequestBody(contentType(parsed), body));

        return ImportResult.of(List.of(scenario), ValidationResult.of(findings));
    }

    /** The declared content type, or null so the specification's own default applies. */
    private static String contentType(Parsed parsed) {
        return parsed.headers.entrySet().stream()
                .filter(entry -> "content-type".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Map<String, String> parseQuery(String rawQuery, List<ValidationFinding> findings) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }

        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);

            if (SecretValueDetector.looksLikeCredential(value)
                    || SecretValueDetector.isCredentialHeader(name)) {
                // Sharper than a header: a query string is written to every access log between the
                // runner and the target.
                findings.add(ValidationFinding.warning("import.curl.query.credential",
                        "/query/" + name,
                        "Query parameter '" + name + "' looked like a credential and was not "
                                + "imported.", null));
                continue;
            }
            query.put(name, value);
        }
        return query;
    }

    /**
     * A readable scenario name derived from the path.
     *
     * <p>{@code /api/orders/123} becomes {@code api-orders}. Numeric segments are dropped because
     * they are almost always an identifier from whoever copied the command, and a scenario called
     * {@code api-orders-4718} tells a later reader nothing.
     */
    private static String nameFor(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isBlank() || segment.matches("\\d+")
                    || segment.matches("[0-9a-fA-F-]{8,}")) {
                continue;
            }
            segments.add(segment.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT));
        }
        return segments.isEmpty() ? "request" : String.join("-", segments);
    }
}
