package com.perfplatform.domain.validation;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a request body can be imported at all, by what its fields are <em>named</em>.
 *
 * <p>The companion to {@link SecretValueDetector}, and it exists because that class cannot solve
 * this. {@code SecretValueDetector} asks whether a <em>value</em> looks like a credential, which
 * catches a bearer token in a header. It cannot catch this:
 *
 * <pre>{@code {"username":"a.smith","password":"Sunflower2019!"}}</pre>
 *
 * <p>Nothing about {@code Sunflower2019!} is token-shaped. It is a real person's real password, in
 * plain text, in a document that would be stored in the database, rendered in the UI, echoed back by
 * the import endpoint and committed to Git by anyone using the GitOps channel. Sign-in is also the
 * request most likely to be worth load-testing, so it is not a rare edge — a HAR capture records it
 * verbatim, and a Postman collection usually has one saved with working values.
 *
 * <p>So the test is on the field name. A body carrying a field named {@code password} is refused
 * <b>whole</b>, never redacted: partial redaction of a structure this class only partly understands
 * is a guess, and a guess that goes wrong here discloses a credential. Callers keep the scenario and
 * drop only its body, reporting the field so a person can supply a dataset or a secret reference.
 *
 * <p>Personal data is refused on the same reasoning. A source recorded from a real session carries a
 * real customer's card number or tax identifier, and a specification is not a place for either —
 * and unlike a password, it will still be valid next year.
 *
 * <h2>Why this reads JSON with a regular expression</h2>
 * Only <em>field names</em> are needed, never the structure and never a value. Matching quoted keys
 * directly keeps this class in {@code platform-domain}, which holds no serialization library by
 * design (Increment 1B), and it cannot be defeated by nesting depth or by a document that does not
 * quite parse. It can in principle over-match, and that is the safe direction: a false positive
 * costs one edit, a false negative costs a rotation.
 */
public final class SensitiveFieldDetector {

    /**
     * Field names that hold a credential.
     *
     * <p>Normalized — lowercase, non-alphanumerics removed — so {@code client_secret},
     * {@code clientSecret} and {@code Client-Secret} are one entry. Matched whole, never as a
     * substring: a field named {@code pin} is a PIN, a field named {@code shipping} is not.
     */
    private static final Set<String> CREDENTIAL_FIELDS = Set.of(
            "password", "passwd", "pwd", "passcode", "passphrase",
            "secret", "clientsecret", "credentials",
            "token", "accesstoken", "refreshtoken", "idtoken", "apitoken",
            "apikey", "authorization", "auth", "privatekey",
            "otp", "totp", "mfacode", "pin",
            "sessionid", "jsessionid");

    /** Field names that hold personal data belonging to a real person. */
    private static final Set<String> PERSONAL_FIELDS = Set.of(
            "ssn", "socialsecuritynumber", "nationalinsurancenumber", "taxid", "tin",
            "cardnumber", "creditcard", "creditcardnumber", "pan", "cvv", "cvc",
            "accountnumber", "routingnumber", "iban",
            "dateofbirth", "dob");

    /** A quoted JSON key: the name, and the colon that makes it a key rather than a value. */
    private static final Pattern JSON_KEY = Pattern.compile("\"([^\"\\\\]{1,64})\"\\s*:");

    /**
     * The same vocabulary as a pattern, for bodies whose structure is not recognised.
     *
     * <p>XML, plain text, anything else. It cannot name a field, so it errs toward refusing.
     */
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)(pass(word|wd|code|phrase)?|client[_-]?secret|access[_-]?token|refresh[_-]?token"
                    + "|api[_-]?key|authorization|ssn|social[_-]?security|card[_-]?number|cvv|cvc"
                    + "|date[_-]?of[_-]?birth)");

    public enum Verdict {
        /** Nothing recognised as sensitive. */
        SAFE,
        /** A field holding a credential. */
        CREDENTIAL,
        /** A field holding personal data. */
        PERSONAL_DATA,
        /** A multipart, file or binary body, which cannot be imported meaningfully. */
        UNSUPPORTED
    }

    /**
     * @param verdict what was found
     * @param field   the offending field's name where one could be identified, otherwise null.
     *                <b>Never the value</b> — a finding is returned over HTTP and written to logs
     */
    public record Scan(Verdict verdict, String field) {

        public boolean isSafe() {
            return verdict == Verdict.SAFE;
        }

        public static Scan safe() {
            return new Scan(Verdict.SAFE, null);
        }
    }

    private SensitiveFieldDetector() {
    }

    /**
     * Judges one request body.
     *
     * @param contentType the declared media type, or null
     * @param body        the body itself
     */
    public static Scan scan(String contentType, String body) {
        if (body == null || body.isBlank()) {
            return Scan.safe();
        }

        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        if (type.startsWith("multipart/") || type.startsWith("application/octet-stream")) {
            // A capture stores these truncated or base64-wrapped and a collection stores a file
            // reference, so what is recorded is not what was sent. Replaying it would test a
            // request nobody made.
            return new Scan(Verdict.UNSUPPORTED, null);
        }
        if (type.contains("json") || looksLikeJson(body)) {
            return scanJsonKeys(body);
        }
        if (type.contains("x-www-form-urlencoded")) {
            return scanFormNames(body);
        }
        return SENSITIVE_TEXT.matcher(body).find()
                ? new Scan(Verdict.CREDENTIAL, null)
                : Scan.safe();
    }

    /**
     * Judges a set of field names directly.
     *
     * <p>For sources that hand over structured parameters rather than a serialized body — a Postman
     * url-encoded body, a form parameter list — so the caller does not have to reassemble a document
     * only for it to be taken apart again.
     */
    public static Scan scanNames(Iterable<String> names) {
        for (String name : names) {
            Scan judged = judge(name);
            if (!judged.isSafe()) {
                return judged;
            }
        }
        return Scan.safe();
    }

    /** Every quoted key in the document, at any depth, without parsing it. */
    private static Scan scanJsonKeys(String body) {
        Set<String> names = new LinkedHashSet<>();
        Matcher keys = JSON_KEY.matcher(body);
        while (keys.find()) {
            names.add(keys.group(1));
        }
        return scanNames(names);
    }

    private static Scan scanFormNames(String body) {
        Set<String> names = new LinkedHashSet<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            names.add(decode(equals < 0 ? pair : pair.substring(0, equals)));
        }
        return scanNames(names);
    }

    /** Judges one field name. */
    private static Scan judge(String name) {
        String normalized = normalize(name);
        if (CREDENTIAL_FIELDS.contains(normalized)) {
            return new Scan(Verdict.CREDENTIAL, name);
        }
        if (PERSONAL_FIELDS.contains(normalized)) {
            return new Scan(Verdict.PERSONAL_DATA, name);
        }
        return Scan.safe();
    }

    /**
     * Whether a body is JSON even though nothing said so.
     *
     * <p>Sources are unreliable about media types — a HAR records what the browser sent, a
     * collection records what the author selected — and a JSON body judged as unstructured text
     * would fall through to the blunter pattern.
     */
    private static boolean looksLikeJson(String body) {
        String trimmed = body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /** {@code client_secret}, {@code clientSecret} and {@code Client-Secret} are the same field. */
    private static String normalize(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Percent-decodes a form parameter name.
     *
     * <p>Hand-rolled rather than {@code URLDecoder}: the domain stays free of assumptions about
     * charset handling in a web layer, and only the name is needed.
     */
    private static String decode(String value) {
        if (value.indexOf('%') < 0 && value.indexOf('+') < 0) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+') {
                decoded.append(' ');
            } else if (c == '%' && i + 2 < value.length()) {
                try {
                    decoded.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                } catch (NumberFormatException e) {
                    decoded.append(c);
                }
            } else {
                decoded.append(c);
            }
        }
        return decoded.toString();
    }
}
