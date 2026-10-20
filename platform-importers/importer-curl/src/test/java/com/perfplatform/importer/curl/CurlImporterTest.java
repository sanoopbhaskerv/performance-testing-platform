package com.perfplatform.importer.curl;

import com.perfplatform.domain.importer.SourceImporter.ImportResult;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cURL importer.
 *
 * <p>Two things are being protected here. The obvious one is that a pasted command becomes a usable
 * scenario. The one that matters more is that a pasted command <em>cannot</em> put a credential, a
 * host, or executable text into the platform — a cURL command copied from browser dev tools carries
 * a live session, and it arrives over HTTP from whoever pasted it.
 */
class CurlImporterTest {

    private final CurlImporter importer = new CurlImporter();

    private ScenarioSpec onlyScenario(ImportResult result) {
        assertThat(result.scenarios()).hasSize(1);
        return result.scenarios().get(0);
    }

    private List<String> codes(ImportResult result) {
        return result.findings().findings().stream().map(f -> f.code()).toList();
    }

    @Nested
    @DisplayName("reading the request")
    class Reading {

        @Test
        @DisplayName("method, path, headers and body are all imported")
        void importsTheWholeRequest() {
            ImportResult result = importer.importFrom("""
                    curl -X POST 'https://api.example.com/v1/orders' \\
                      -H 'Content-Type: application/json' \\
                      -H 'Accept: application/json' \\
                      -d '{"sku":"ABC","quantity":2}'
                    """);

            ScenarioSpec scenario = onlyScenario(result);
            assertThat(scenario.methodOrDefault()).isEqualTo("POST");
            assertThat(scenario.path()).isEqualTo("/v1/orders");
            assertThat(scenario.headers())
                    .containsEntry("Content-Type", "application/json")
                    .containsEntry("Accept", "application/json");
            assertThat(scenario.body().content()).isEqualTo("{\"sku\":\"ABC\",\"quantity\":2}");
            assertThat(scenario.body().contentTypeOrDefault()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("query parameters come across as query, not as part of the path")
        void separatesQueryFromPath() {
            ScenarioSpec scenario = onlyScenario(importer.importFrom(
                    "curl 'https://api.example.com/v1/orders?status=open&page=2'"));

            assertThat(scenario.path()).isEqualTo("/v1/orders");
            assertThat(scenario.query())
                    .containsEntry("status", "open")
                    .containsEntry("page", "2");
        }

        /** cURL's own rule, and the one every pasted command relies on. */
        @Test
        @DisplayName("-d without -X implies POST, as cURL itself does")
        void dataImpliesPost() {
            assertThat(onlyScenario(importer.importFrom(
                    "curl https://api.example.com/orders -d '{}'")).methodOrDefault())
                    .isEqualTo("POST");
        }

        @Test
        @DisplayName("a bare URL is a GET")
        void bareUrlIsGet() {
            assertThat(onlyScenario(importer.importFrom("curl https://api.example.com/health"))
                    .methodOrDefault()).isEqualTo("GET");
        }

        /** Dev tools emit these constantly; a line continuation must not split an argument. */
        @Test
        @DisplayName("a multi-line command with continuations reads as one command")
        void handlesLineContinuations() {
            ScenarioSpec scenario = onlyScenario(importer.importFrom(
                    "curl \\\n  -X PUT \\\n  'https://api.example.com/v1/items/7' \\\n  -d 'x=1'"));

            assertThat(scenario.methodOrDefault()).isEqualTo("PUT");
            assertThat(scenario.path()).isEqualTo("/v1/items/7");
        }

        /**
         * Without this, {@code --retry 3} would be read as a URL and the import would produce a
         * scenario addressing {@code /3}.
         */
        @Test
        @DisplayName("a flag's value is never mistaken for the URL")
        void flagValuesAreNotUrls() {
            ScenarioSpec scenario = onlyScenario(importer.importFrom(
                    "curl --retry 3 --max-time 30 -A 'Mozilla/5.0' https://api.example.com/health"));

            assertThat(scenario.path()).isEqualTo("/health");
        }

        @Test
        @DisplayName("repeated -d flags are joined, as cURL does")
        void repeatedDataIsJoined() {
            assertThat(onlyScenario(importer.importFrom(
                    "curl https://api.example.com/form -d 'a=1' -d 'b=2'")).body().content())
                    .isEqualTo("a=1&b=2");
        }
    }

    @Nested
    @DisplayName("what must never be imported")
    class Refusals {

        /**
         * The reason this importer needs care at all. A command copied from dev tools carries a live
         * session, and the specification it produces is stored, rendered and committed to Git.
         */
        @Test
        @DisplayName("an Authorization header is stripped and its removal reported")
        void stripsAuthorizationHeader() {
            ImportResult result = importer.importFrom("""
                    curl 'https://api.example.com/v1/orders' \\
                      -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.secret' \\
                      -H 'Accept: application/json'
                    """);

            assertThat(onlyScenario(result).headers())
                    .containsOnlyKeys("Accept");
            assertThat(codes(result)).contains("import.curl.credentials.stripped");
        }

        @Test
        @DisplayName("cookies are stripped, from the header and from -b alike")
        void stripsCookies() {
            ImportResult fromHeader = importer.importFrom(
                    "curl https://api.example.com/v1/me -H 'Cookie: session=abc123'");
            ImportResult fromFlag = importer.importFrom(
                    "curl https://api.example.com/v1/me -b 'session=abc123'");

            assertThat(onlyScenario(fromHeader).headers()).isEmpty();
            assertThat(codes(fromHeader)).contains("import.curl.credentials.stripped");
            assertThat(codes(fromFlag)).contains("import.curl.cookies.stripped");
        }

        @Test
        @DisplayName("-u basic auth is consumed and never read into the draft")
        void stripsBasicAuth() {
            ImportResult result = importer.importFrom(
                    "curl -u admin:hunter2 https://api.example.com/v1/admin");

            assertThat(onlyScenario(result).path()).isEqualTo("/v1/admin");
            assertThat(codes(result)).contains("import.curl.credentials.stripped");
        }

        /** A finding is logged and returned over HTTP; one quoting the secret would spread it. */
        @Test
        @DisplayName("no finding ever echoes the credential it removed")
        void findingsNeverEchoTheCredential() {
            String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZWNyZXQifQ.s3cr3tv4lu3";
            ImportResult result = importer.importFrom(
                    "curl https://api.example.com/v1/orders -H 'Authorization: Bearer " + token + "'");

            assertThat(result.findings().findings())
                    .allSatisfy(finding -> assertThat(
                            finding.message() + String.valueOf(finding.remediation()))
                            .doesNotContain(token)
                            .doesNotContain("hunter2"));
        }

        /**
         * Where a run's traffic goes is resolved from the team's registered targets. A specification
         * that could name its own host would make the target policy advisory.
         */
        @Test
        @DisplayName("the host is dropped, and the drop is reported")
        void dropsTheHost() {
            ImportResult result = importer.importFrom("curl https://prod.example.com/v1/orders");

            assertThat(onlyScenario(result).path()).isEqualTo("/v1/orders");
            assertThat(codes(result)).contains("import.curl.host.dropped");
            assertThat(result.findings().findings())
                    .anySatisfy(f -> assertThat(f.message()).contains("prod.example.com"));
        }

        @Test
        @DisplayName("a credential in a query parameter is dropped")
        void dropsCredentialQueryParameters() {
            ImportResult result = importer.importFrom(
                    "curl 'https://api.example.com/v1/orders?page=1&api_key=AKIAIOSFODNN7EXAMPLE'");

            assertThat(onlyScenario(result).query()).containsOnlyKeys("page");
            assertThat(codes(result)).contains("import.curl.query.credential");
        }

        /**
         * Client-negotiated headers, not authored ones. Importing them pins the test to whatever the
         * browser happened to send on the day the command was copied.
         */
        @Test
        @DisplayName("Host, Content-Length and Connection are not imported")
        void dropsClientManagedHeaders() {
            ImportResult result = importer.importFrom("""
                    curl https://api.example.com/v1/orders \\
                      -H 'Host: api.example.com' -H 'Content-Length: 42' \\
                      -H 'Connection: keep-alive' -H 'Accept: application/json'
                    """);

            assertThat(onlyScenario(result).headers()).containsOnlyKeys("Accept");
        }
    }

    @Nested
    @DisplayName("nothing is executed")
    class NoExecution {

        /**
         * The command is parsed as arguments and never handed to a shell, so shell metacharacters
         * are inert text. This asserts the *absence* of interpretation: the substitution survives as
         * literal characters rather than being evaluated or stripped.
         */
        @Test
        @DisplayName("shell metacharacters are inert text, not commands")
        void shellMetacharactersAreInert() {
            ImportResult result = importer.importFrom(
                    "curl 'https://api.example.com/v1/orders' -d '{\"note\":\"$(whoami); rm -rf /\"}'");

            assertThat(onlyScenario(result).body().content())
                    .isEqualTo("{\"note\":\"$(whoami); rm -rf /\"}");
        }

        @Test
        @DisplayName("a backtick substitution is text too")
        void backticksAreInert() {
            assertThat(onlyScenario(importer.importFrom(
                    "curl https://api.example.com/x -d 'v=`id`'")).body().content())
                    .isEqualTo("v=`id`");
        }
    }

    @Nested
    @DisplayName("input that is not a cURL command")
    class BadInput {

        /**
         * A consumer pasting the wrong thing is ordinary and deserves an explanation, not a stack
         * trace — and an exception here would surface as a 500 from an endpoint whose whole purpose
         * is to say what is wrong.
         */
        @Test
        @DisplayName("empty, blank and non-cURL input are reported rather than thrown")
        void badInputIsReported() {
            assertThat(codes(importer.importFrom(""))).contains("import.curl.empty");
            assertThat(codes(importer.importFrom("   \n  "))).contains("import.curl.empty");
            assertThat(codes(importer.importFrom("wget https://example.com")))
                    .contains("import.curl.notACurlCommand");
        }

        @Test
        @DisplayName("a command with no URL is reported")
        void missingUrlIsReported() {
            ImportResult result = importer.importFrom("curl -X POST -H 'Accept: */*'");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).contains("import.curl.url.missing");
        }

        @Test
        @DisplayName("an unterminated quote does not throw")
        void unterminatedQuoteDoesNotThrow() {
            assertThat(importer.importFrom("curl 'https://api.example.com/v1/orders")).isNotNull();
        }
    }

    @Nested
    @DisplayName("the draft it produces")
    class Draft {

        @Test
        @DisplayName("the scenario is named after the path, without identifiers")
        void namesFromPath() {
            assertThat(onlyScenario(importer.importFrom(
                    "curl https://api.example.com/v1/orders/8817/items")).name())
                    .isEqualTo("v1-orders-items");
        }

        /**
         * Honest about what it produced. Every virtual user sending one payload measures the
         * target's behaviour on a single repeated request, which is rarely what a write test wants.
         */
        @Test
        @DisplayName("a static body is flagged as something to vary with a dataset")
        void reportsStaticBody() {
            assertThat(codes(importer.importFrom(
                    "curl https://api.example.com/orders -d '{\"sku\":\"ABC\"}'")))
                    .contains("import.curl.body.static");
        }

        @Test
        @DisplayName("it declares the source type it understands")
        void declaresItsSourceType() {
            assertThat(importer.sourceType())
                    .isEqualTo(com.perfplatform.domain.spec.SourceType.CURL);
        }
    }
}
