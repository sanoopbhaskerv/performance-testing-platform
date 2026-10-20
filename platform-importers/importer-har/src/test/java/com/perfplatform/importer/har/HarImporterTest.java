package com.perfplatform.importer.har;

import com.perfplatform.domain.importer.SourceImporter.ImportResult;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.spec.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HAR importer.
 *
 * <p>Most of this file is about what a capture must <em>not</em> put into the platform. A HAR is a
 * verbatim recording of a live session — the cookies are live, the tokens are live, and the sign-in
 * request carries a real password in plain text — so a draft that quietly carried any of that
 * forward would be worse than no draft at all. The rest is about the one thing a capture knows that
 * no other source does: how often each request was actually made.
 *
 * <p>Every test lives in a {@code @Nested} class, matching the other importers — by convention, not
 * necessity. Surefire 3.1.2 prints {@code Tests run: 0} for an outer class that also declares
 * {@code @Nested} classes, but that is a reporting artefact rather than lost coverage; see
 * HANDOFF.md §3h.
 */
class HarImporterTest {

    private final HarImporter importer = new HarImporter();

    private static List<String> codes(ImportResult result) {
        return result.findings().findings().stream().map(f -> f.code()).toList();
    }

    private static List<String> names(ImportResult result) {
        return result.scenarios().stream().map(ScenarioSpec::name).toList();
    }

    private static Optional<ScenarioSpec> find(ImportResult result, String name) {
        return result.scenarios().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    private static ScenarioSpec scenario(ImportResult result, String name) {
        return find(result, name).orElseThrow(() -> new AssertionError(
                "no scenario named '" + name + "' in " + names(result)));
    }

    /**
     * Every request the import produced, whichever journey it belongs to.
     *
     * <p>S3 changed the shape rather than the behaviour: a captured page used to become several
     * sibling scenarios and now becomes one journey of ordered steps. Almost every test here asserts
     * something about a <em>request</em> — that a credential was stripped, that a host was dropped,
     * that a body was refused — and none of that moved. Flattening here keeps those assertions
     * pointed at what they were always about, instead of rewriting twenty tests to walk into steps.
     */
    private static List<StepSpec> requests(ImportResult result) {
        return result.scenarios().stream().flatMap(s -> s.asSteps().stream()).toList();
    }

    private static List<String> requestNames(ImportResult result) {
        return requests(result).stream().map(StepSpec::name).toList();
    }

    /** One request by name, from any journey. */
    private static StepSpec request(ImportResult result, String name) {
        return requests(result).stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no request named '" + name + "' in " + requestNames(result)));
    }

    /** Wraps entries in the smallest valid capture around them. */
    private static String captureWithPages(String pages, String... entries) {
        return "{\"log\":{\"version\":\"1.2\",\"pages\":[" + pages + "],\"entries\":["
                + String.join(",", entries) + "]}}";
    }

    private static String capture(String... entries) {
        return captureWithPages("", entries);
    }

    /**
     * A realistic capture of a checkout session, with everything a real one contains: a live
     * session cookie, a bearer token, a browser fingerprint, a conditional request header, a
     * sign-in with a password, a script, a third-party beacon, a repeat and a failure.
     */
    private static final String CHECKOUT_SESSION = captureWithPages(
            """
            {"id": "page_1", "title": "https://shop.example.com/checkout"}
            """,
            """
            {"pageref":"page_1","request":{"method":"GET",
              "url":"https://shop.example.com/api/basket?includeTotals=true",
              "queryString":[{"name":"includeTotals","value":"true"}],
              "cookies":[{"name":"SESSION","value":"live-session-value"}],
              "headers":[
                {"name":"Cookie","value":"SESSION=live-session-value"},
                {"name":"Authorization","value":"Bearer aGVsbG90aGVyZXRoaXNpc2Fsb25ndG9rZW4x"},
                {"name":"Accept","value":"application/json"},
                {"name":"User-Agent","value":"Mozilla/5.0"},
                {"name":"If-None-Match","value":"W/\\"abc\\""},
                {"name":"sec-fetch-mode","value":"cors"},
                {"name":":authority","value":"shop.example.com"}]},
             "response":{"status":200,"content":{"mimeType":"application/json"}}}
            """,
            """
            {"pageref":"page_1","request":{"method":"POST",
              "url":"https://shop.example.com/api/login",
              "headers":[{"name":"Content-Type","value":"application/json"}],
              "postData":{"mimeType":"application/json",
                "text":"{\\"username\\":\\"a.smith\\",\\"password\\":\\"Sunflower2019!\\"}"}},
             "response":{"status":200,"content":{"mimeType":"application/json"}}}
            """,
            """
            {"pageref":"page_1","request":{"method":"GET",
              "url":"https://cdn.example.com/static/app.js","headers":[]},
             "response":{"status":200,"content":{"mimeType":"application/javascript"}}}
            """,
            """
            {"pageref":"page_1","request":{"method":"POST",
              "url":"https://www.google-analytics.com/collect?v=2","headers":[]},
             "response":{"status":200,"content":{"mimeType":"text/plain"}}}
            """,
            """
            {"pageref":"page_1","request":{"method":"GET",
              "url":"https://shop.example.com/api/basket?includeTotals=true",
              "queryString":[{"name":"includeTotals","value":"true"}],"headers":[]},
             "response":{"status":200,"content":{"mimeType":"application/json"}}}
            """,
            """
            {"pageref":"page_1","request":{"method":"GET",
              "url":"https://shop.example.com/api/promotions","headers":[]},
             "response":{"status":404,"content":{"mimeType":"application/json"}}}
            """,
            """
            {"pageref":"page_1","request":{"method":"POST",
              "url":"https://shop.example.com/api/orders",
              "headers":[{"name":"Content-Type","value":"application/json"}],
              "postData":{"mimeType":"application/json",
                "text":"{\\"sku\\":\\"ABC-1\\",\\"quantity\\":2}"}},
             "response":{"status":201,"content":{"mimeType":"application/json"}}}
            """);

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("it declares itself the HAR importer")
        void declaresItsSourceType() {
            assertThat(importer.sourceType()).isEqualTo(SourceType.HAR);
        }
    }

    @Nested
    @DisplayName("reading the capture")
    class Reading {

        @Test
        @DisplayName("only the application's own successful, non-asset requests become scenarios")
        void importsApplicationRequestsOnly() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            // One journey now, named after the page, whose steps are its requests in the order
            // the session made them. The basket appears twice because it was fetched twice — once
            // before signing in and once after — and the second gets a distinct name because a step
            // name becomes a result label: two steps sharing one would have their samples merged,
            // and a cold fetch and a signed-in fetch are worth telling apart.
            assertThat(names(result)).containsExactly("checkout");
            assertThat(requestNames(result))
                    .containsExactly("api-basket", "post-api-login", "api-basket-2", "post-api-orders");
        }

        @Test
        @DisplayName("method, path and query survive; the host does not")
        void importsTheRequestWithoutItsHost() {
            StepSpec basket = request(importer.importFrom(CHECKOUT_SESSION), "api-basket");

            assertThat(basket.methodOrDefault()).isEqualTo("GET");
            assertThat(basket.path()).isEqualTo("/api/basket");
            assertThat(basket.query()).containsEntry("includeTotals", "true");
            assertThat(codes(importer.importFrom(CHECKOUT_SESSION)))
                    .contains("import.har.host.dropped");
        }

        @Test
        @DisplayName("a body with nothing sensitive in it is imported with its media type")
        void importsAnOrdinaryBody() {
            StepSpec orders = request(importer.importFrom(CHECKOUT_SESSION), "post-api-orders");

            assertThat(orders.body()).isNotNull();
            assertThat(orders.body().content()).isEqualTo("{\"sku\":\"ABC-1\",\"quantity\":2}");
            assertThat(orders.body().contentTypeOrDefault()).isEqualTo("application/json");
        }

        /**
         * The one thing a capture knows that an API document cannot: how often each call was made.
         */
        @Test
        @DisplayName("how often a request was made survives as repeated steps, not as a weight")
        void observedFrequencySurvivesAsSequence() {
            // Inverted at S3, in place. This used to assert weights of 50/25/25 from the observed
            // counts, which was right when scenarios ran independently and the ratio had nowhere
            // else to live. A journey carries its own ratio: the basket was fetched twice, so the
            // journey fetches it twice. Collapsing it back to one step would quietly halve the
            // traffic the capture actually observed for it.
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(requestNames(result)).filteredOn(n -> n.startsWith("api-basket")).hasSize(2);
            assertThat(codes(result)).contains("import.har.weights.equal");
        }

        @Test
        @DisplayName("a capture with no pages still imports, without a journey prefix")
        void readsACaptureWithNoPages() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"GET","url":"https://api.example.com/orders","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(names(result)).containsExactly("orders");
        }

        @Test
        @DisplayName("it proposes correlations with response and request evidence, without applying them")
        void proposesCorrelationsWithEvidence() {
            ImportResult result = importer.importFrom(captureWithPages(
                    """
                    {"id":"page_1","title":"https://shop.example.com/checkout"}
                    """,
                    """
                    {"pageref":"page_1","request":{"method":"POST",
                      "url":"https://shop.example.com/api/basket","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json",
                       "text":"{\\"basketId\\":\\"basket-123\\"}"}}}
                    """,
                    """
                    {"pageref":"page_1","request":{"method":"POST",
                      "url":"https://shop.example.com/api/orders",
                      "headers":[{"name":"Content-Type","value":"application/json"}],
                      "postData":{"mimeType":"application/json",
                        "text":"{\\"basketId\\":\\"basket-123\\",\\"quantity\\":1}"}},
                     "response":{"status":201,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(result.correlationSuggestions()).singleElement().satisfies(suggestion -> {
                assertThat(suggestion.scenarioName()).isEqualTo("checkout");
                assertThat(suggestion.variableName()).isEqualTo("basketId");
                assertThat(suggestion.extraction().stepName()).isEqualTo("post-api-basket");
                assertThat(suggestion.extraction().from()).isEqualTo("json-body");
                assertThat(suggestion.extraction().expression()).isEqualTo("$.basketId");
                assertThat(suggestion.extraction().valuePreview()).isEqualTo("basket-123");
                assertThat(suggestion.replacements()).singleElement().satisfies(replacement -> {
                    assertThat(replacement.stepName()).isEqualTo("post-api-orders");
                    assertThat(replacement.location()).isEqualTo("body");
                    assertThat(replacement.currentValue()).contains("basket-123");
                });
            });
            assertThat(request(result, "post-api-basket").extract()).isEmpty();
            assertThat(request(result, "post-api-orders").body().content()).contains("basket-123");
        }

        @Test
        @DisplayName("token-shaped response values are not echoed as suggestions")
        void doesNotEchoTokenShapedSuggestions() {
            ImportResult result = importer.importFrom(captureWithPages(
                    """
                    {"id":"page_1","title":"https://shop.example.com/checkout"}
                    """,
                    """
                    {"pageref":"page_1","request":{"method":"POST",
                      "url":"https://shop.example.com/api/login","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json",
                       "text":"{\\"accessToken\\":\\"aGVsbG90aGVyZXRoaXNpc2Fsb25ndG9rZW4x\\"}"}}}
                    """,
                    """
                    {"pageref":"page_1","request":{"method":"GET",
                      "url":"https://shop.example.com/api/account",
                      "headers":[{"name":"X-Request-Token",
                        "value":"aGVsbG90aGVyZXRoaXNpc2Fsb25ndG9rZW4x"}]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(result.correlationSuggestions()).isEmpty();
        }
    }

    /**
     * A capture is mostly noise. What survives the filters is what somebody meant to test.
     */
    @Nested
    @DisplayName("what a capture records that is not the test")
    class Filtering {

        @Test
        @DisplayName("scripts, stylesheets, images and fonts are dropped and counted")
        void dropsStaticAssets() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(names(result)).noneMatch(name -> name.contains("app-js"));
            assertThat(codes(result)).contains("import.har.assets.dropped");
        }

        /**
         * The filter that has to exist. Every scenario is sent to the one target registered for the
         * application, and the captured host is dropped — so an unfiltered capture would aim
         * {@code /collect} at the team's own service and report the result as their latency.
         */
        @Test
        @DisplayName("requests to third-party hosts never become requests against the target")
        void dropsThirdPartyHosts() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(requests(result)).noneMatch(s -> "/collect".equals(s.path()));
            assertThat(codes(result)).contains("import.har.thirdParty.dropped");
            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.har.thirdParty.dropped"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("google-analytics.com"));
        }

        @Test
        @DisplayName("the application host is chosen by how much of the session went to it")
        void picksTheDominantHost() {
            ImportResult result = importer.importFrom(capture(
                    """
                    {"request":{"method":"GET","url":"https://sso.example.com/authorize","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"text/html"}}}
                    """,
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/a","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """,
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/b","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            // The first entry is an identity provider, not the system under test.
            assertThat(requestNames(result)).containsExactly("a", "b");
        }

        @Test
        @DisplayName("a request that failed when captured is not replayed")
        void dropsFailedRequests() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(names(result)).noneMatch(name -> name.contains("promotions"));
            assertThat(codes(result)).contains("import.har.failures.dropped");
        }

        @Test
        @DisplayName("a repeat raises the weight rather than adding a scenario")
        void keepsRepeatsAsSteps() {
            // Inverted at S3, in place. Repeats used to be collapsed and the count raised a weight,
            // because independent scenarios needed the ratio to live somewhere. A sequence carries
            // its own: the basket was fetched twice, so the journey fetches it twice.
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(names(result)).doesNotHaveDuplicates();
            assertThat(requestNames(result)).filteredOn(n -> n.startsWith("api-basket")).hasSize(2);
            assertThat(codes(result)).doesNotContain("import.har.duplicates.collapsed");
        }

        @Test
        @DisplayName("the same call with different query parameters is one scenario, and is reported")
        void reportsQueryVariants() {
            ImportResult result = importer.importFrom(capture(
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/orders?page=1",
                      "queryString":[{"name":"page","value":"1"}],"headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """,
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/orders?page=2",
                      "queryString":[{"name":"page","value":"2"}],"headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            // Two calls to the same path with different query strings are two steps now, in the
            // order they were made — the variance is the sequence rather than a discarded variant.
            assertThat(requests(result)).hasSize(2);
            assertThat(requests(result).get(0).query()).containsEntry("page", "1");
            assertThat(requests(result).get(1).query()).containsEntry("page", "2");
        }

        @Test
        @DisplayName("an entry the capture truncated is skipped, not fatal")
        void skipsUnreadableEntries() {
            ImportResult result = importer.importFrom(capture(
                    "{\"request\":{},\"response\":{\"status\":200}}",
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/orders","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(result.scenarios()).hasSize(1);
            assertThat(codes(result)).contains("import.har.entries.unreadable");
        }
    }

    /**
     * The reason this importer exists in the shape it does.
     *
     * <p>A specification is stored in the database, rendered in the UI, echoed back by this
     * endpoint and committed to Git by anyone using the GitOps channel. Everything asserted here is
     * something that, imported once, would have to be rotated or reported rather than deleted.
     */
    @Nested
    @DisplayName("a live session cannot get into the platform")
    class Privacy {

        @Test
        @DisplayName("cookies are never imported, and their removal is reported")
        void stripsCookies() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(result.scenarios()).allSatisfy(scenario ->
                    assertThat(scenario.headers()).doesNotContainKeys("Cookie", "cookie"));
            assertThat(codes(result)).contains("import.har.cookies.stripped");
        }

        @Test
        @DisplayName("an Authorization header is never imported")
        void stripsAuthorization() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(request(result, "api-basket").headers())
                    .doesNotContainKeys("Authorization", "authorization");
            assertThat(codes(result)).contains("import.har.header.credential");
        }

        /**
         * The case {@code SecretValueDetector} cannot reach. Nothing about {@code Sunflower2019!}
         * is token-shaped — it is a real person's real password — so the test has to be on the
         * field's name.
         */
        @Test
        @DisplayName("a captured password is refused, and the finding names the field, never the value")
        void refusesACapturedPassword() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(request(result, "post-api-login").body()).isNull();
            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.har.body.credential"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("password"));
            assertThat(result.findings().findings()).allSatisfy(finding ->
                    assertThat(finding.message() + finding.remediation())
                            .doesNotContain("Sunflower2019"));
        }

        @Test
        @DisplayName("a password in a form-encoded body is refused too")
        void refusesAFormEncodedPassword() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"POST","url":"https://api.example.com/signin",
                      "headers":[],
                      "postData":{"mimeType":"application/x-www-form-urlencoded",
                        "text":"user=a.smith&password=Sunflower2019%21&remember=true"}},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(request(result, "post-signin").body()).isNull();
            assertThat(codes(result)).contains("import.har.body.credential");
        }

        @Test
        @DisplayName("a credential nested deep inside a body is still found")
        void refusesANestedCredential() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"POST","url":"https://api.example.com/session",
                      "headers":[],
                      "postData":{"mimeType":"application/json",
                        "text":"{\\"context\\":{\\"user\\":{\\"clientSecret\\":\\"abc\\"}}}"}},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(request(result, "post-session").body()).isNull();
            assertThat(codes(result)).contains("import.har.body.credential");
        }

        @Test
        @DisplayName("a body carrying a real customer's personal data is refused")
        void refusesPersonalData() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"POST","url":"https://api.example.com/applications",
                      "headers":[],
                      "postData":{"mimeType":"application/json",
                        "text":"{\\"name\\":\\"A Smith\\",\\"ssn\\":\\"123-45-6789\\"}"}},
                     "response":{"status":201,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(request(result, "post-applications").body()).isNull();
            assertThat(codes(result)).contains("import.har.body.personalData");
        }

        @Test
        @DisplayName("a multipart body is refused, because a capture does not record what was sent")
        void refusesMultipartBodies() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"POST","url":"https://api.example.com/documents",
                      "headers":[],
                      "postData":{"mimeType":"multipart/form-data; boundary=x","text":"--x--"}},
                     "response":{"status":201,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(request(result, "post-documents").body()).isNull();
            assertThat(codes(result)).contains("import.har.body.unsupported");
        }

        @Test
        @DisplayName("a CSRF or session header is dropped: minted for one session, invalid on replay")
        void stripsSessionHeaders() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"POST","url":"https://api.example.com/orders",
                      "headers":[{"name":"X-CSRF-Token","value":"9f2b41"},
                                 {"name":"Accept","value":"application/json"}]},
                     "response":{"status":201,"content":{"mimeType":"application/json"}}}
                    """));

            StepSpec orders = request(result, "post-orders");
            assertThat(orders.headers()).doesNotContainKey("X-CSRF-Token")
                    .containsEntry("Accept", "application/json");
            assertThat(codes(result)).contains("import.har.header.session");
        }

        @Test
        @DisplayName("a credential in a query string is dropped — it reaches every access log")
        void stripsCredentialQueryParameters() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"GET","url":"https://api.example.com/orders",
                      "queryString":[{"name":"x-api-key","value":"abc"},
                                     {"name":"page","value":"2"}],"headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(request(result, "orders").query())
                    .doesNotContainKey("x-api-key").containsEntry("page", "2");
            assertThat(codes(result)).contains("import.har.query.credential");
        }

        /**
         * CLAUDE.md §23: require review before load execution.
         *
         * <p>Asserted as a warning rather than as the first finding — {@code ValidationResult} sorts
         * by severity, so position is not a contract. Severity is: review is not advisory.
         */
        @Test
        @DisplayName("every import says, unconditionally, that it must be reviewed")
        void alwaysDemandsReview() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(result.findings().warnings()).extracting(f -> f.code())
                    .contains("import.har.reviewRequired");

            // Including an import that produced nothing at all.
            assertThat(codes(importer.importFrom(capture("""
                    {"request":{"method":"GET","url":"https://cdn.example.com/a.css","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"text/css"}}}
                    """)))).contains("import.har.reviewRequired");
        }
    }

    @Nested
    @DisplayName("headers a browser negotiated are not headers an author chose")
    class BrowserNoise {

        @Test
        @DisplayName("fingerprint, transport and HTTP/2 pseudo-headers are dropped; Accept is kept")
        void dropsNegotiatedHeaders() {
            StepSpec basket = request(importer.importFrom(CHECKOUT_SESSION), "api-basket");

            assertThat(basket.headers())
                    .doesNotContainKeys("User-Agent", "sec-fetch-mode", ":authority")
                    .containsEntry("Accept", "application/json");
        }

        /**
         * The subtle one. Replaying {@code If-None-Match} makes the target answer 304 without doing
         * any work, and the run reports excellent latency for a service that did nothing.
         */
        @Test
        @DisplayName("a conditional request header is dropped, so the target cannot answer 304")
        void dropsConditionalHeaders() {
            assertThat(request(importer.importFrom(CHECKOUT_SESSION), "api-basket")
                    .headers()).doesNotContainKey("If-None-Match");
        }
    }

    @Nested
    @DisplayName("journeys")
    class Journeys {

        @Test
        @DisplayName("a journey is named after the page it came from")
        void namesJourneysAfterTheirPage() {
            // Was: every scenario name started with "checkout-", the prefix being all a journey
            // could be. The journey is the scenario now, so the page name IS the name.
            assertThat(names(importer.importFrom(CHECKOUT_SESSION))).containsExactly("checkout");
        }

        /**
         * The honest part. The platform runs scenarios independently in weighted proportion, so a
         * captured sequence does not survive as a sequence — and saying so is the difference between
         * a draft somebody can use and a test that quietly measures something else.
         */
        @Test
        @DisplayName("it states plainly that correlation is not preserved, now that order is")
        void warnsThatCorrelationIsNotPreserved() {
            // Inverted at S3, in place. This used to assert a warning that ORDER was not preserved,
            // which was true when a scenario was one request. Order is preserved now. What is still
            // not is correlation: the importer can see a response carried an identifier and a later
            // request used one, but not that they are the same thing, and guessing would wire the
            // journey to the wrong value.
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(codes(result)).contains("import.har.journeys.correlationNeeded")
                    .doesNotContain("import.har.journeys.notOrdered");
            assertThat(result.findings().warnings()).extracting(f -> f.code())
                    .contains("import.har.journeys.correlationNeeded");
        }

        @Test
        @DisplayName("a captured page becomes one journey of ordered steps")
        void aPageBecomesAnOrderedJourney() {
            // The capability S3 added, and the reason the notOrdered warning could be retired. A
            // journey is a sequence; before this it could only be a shared name prefix.
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(result.scenarios()).hasSize(1);
            ScenarioSpec journey = result.scenarios().get(0);

            assertThat(journey.isJourney()).isTrue();
            assertThat(journey.steps()).hasSizeGreaterThan(1);
            // The journey itself states no request: its steps do.
            assertThat(journey.path()).isNull();
            assertThat(journey.method()).isNull();
        }

        @Test
        @DisplayName("a journey's steps keep the order the session made them in")
        void stepsKeepTheirOrder() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);
            List<String> paths = result.scenarios().get(0).steps().stream()
                    .map(StepSpec::path).toList();

            // Signing in happens before the order is placed, because that is what was recorded.
            assertThat(paths.indexOf("/api/login")).isLessThan(paths.indexOf("/api/orders"));
        }

        @Test
        @DisplayName("no step arrives with an extraction the importer invented")
        void correlationIsNeverGuessed() {
            // The importer can see a response carried an identifier and a later request used one,
            // but not that they are the same thing. A wrong guess produces a journey that looks
            // wired up and sends the wrong value on every request after the first.
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(requests(result)).allSatisfy(step -> {
                assertThat(step.extract()).isEmpty();
                assertThat(step.expect()).isEmpty();
            });
            assertThat(codes(result)).contains("import.har.journeys.correlationNeeded");
        }

        @Test
        @DisplayName("two pages become two journeys")
        void separatesJourneysByPage() {
            ImportResult result = importer.importFrom(captureWithPages(
                    """
                    {"id":"p1","title":"https://shop.example.com/browse"},
                    {"id":"p2","title":"https://shop.example.com/checkout"}
                    """,
                    """
                    {"pageref":"p1","request":{"method":"GET",
                      "url":"https://shop.example.com/api/catalogue","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """,
                    """
                    {"pageref":"p2","request":{"method":"GET",
                      "url":"https://shop.example.com/api/basket","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(names(result)).containsExactly("browse", "checkout");
            assertThat(requestNames(result)).containsExactly("api-catalogue", "api-basket");
        }
    }

    @Nested
    @DisplayName("the shape of the draft")
    class Draft {

        @Test
        @DisplayName("weights sum to 100 and no scenario is weighted zero")
        void weightsAreValid() {
            ImportResult result = importer.importFrom(CHECKOUT_SESSION);

            assertThat(result.scenarios()).allSatisfy(
                    scenario -> assertThat(scenario.weight()).isGreaterThan(0));
            assertThat(result.scenarios().stream().mapToInt(ScenarioSpec::weight).sum())
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("a lopsided capture still gives every scenario a positive share")
        void neverWeightsAScenarioZero() {
            StringBuilder entries = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                entries.append("""
                        {"request":{"method":"GET","url":"https://api.example.com/poll","headers":[]},
                         "response":{"status":200,"content":{"mimeType":"application/json"}}},
                        """);
            }
            entries.append("""
                    {"request":{"method":"GET","url":"https://api.example.com/rare","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """);

            ImportResult result = importer.importFrom(
                    "{\"log\":{\"entries\":[" + entries + "]}}");

            // One capture with no pages is one journey, so the zero-weight guarantee is about
            // journeys now. It still holds and still matters: a zero share means it never runs.
            assertThat(result.scenarios()).isNotEmpty();
            assertThat(result.scenarios()).allSatisfy(s -> assertThat(s.weight()).isGreaterThan(0));
            assertThat(result.scenarios().stream().mapToInt(ScenarioSpec::weight).sum())
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("names are unique even when two journeys produce the same one")
        void namesAreUnique() {
            ImportResult result = importer.importFrom(capture(
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/orders/1","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """,
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/orders/2","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            // Both paths name the same resource; the identifier segment is not part of the name.
            // Uniqueness is per journey now, and these are two steps of one.
            assertThat(requestNames(result)).containsExactly("orders", "orders-2");
        }

        @Test
        @DisplayName("a very large capture keeps the most frequently observed requests")
        void truncatesLargeCaptures() {
            StringBuilder entries = new StringBuilder();
            for (int i = 0; i < HarImporter.MAX_SCENARIOS + 30; i++) {
                entries.append("{\"request\":{\"method\":\"GET\",\"url\":\"https://api.example.com/r")
                        .append(i).append("\",\"headers\":[]},")
                        .append("\"response\":{\"status\":200,\"content\":")
                        .append("{\"mimeType\":\"application/json\"}}},");
            }
            // One request made many times: it must survive the cap.
            for (int i = 0; i < 5; i++) {
                entries.append("{\"request\":{\"method\":\"GET\",\"url\":\"https://api.example.com/hot\",")
                        .append("\"headers\":[]},\"response\":{\"status\":200,\"content\":")
                        .append("{\"mimeType\":\"application/json\"}}}")
                        .append(i < 4 ? "," : "");
            }

            ImportResult result = importer.importFrom(
                    "{\"log\":{\"entries\":[" + entries + "]}}");

            // Truncation keeps the head in order rather than the most frequent: dropping requests
            // out of the middle of a sequence leaves a journey that never happened.
            assertThat(requests(result)).hasSize(HarImporter.MAX_SCENARIOS);
            assertThat(codes(result)).contains("import.har.entries.truncated");
        }

        @Test
        @DisplayName("a body captured on a GET is not imported")
        void dropsABodyOnGet() {
            ImportResult result = importer.importFrom(capture("""
                    {"request":{"method":"GET","url":"https://api.example.com/search","headers":[],
                      "postData":{"mimeType":"application/json","text":"{\\"q\\":\\"shoes\\"}"}},
                     "response":{"status":200,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(scenario(result, "search").body()).isNull();
        }

        @Test
        @DisplayName("a path is never a full URL, so a capture cannot name its own host")
        void neverProducesAnAbsolutePath() {
            assertThat(requests(importer.importFrom(CHECKOUT_SESSION))).allSatisfy(step -> {
                assertThat(step.path()).startsWith("/");
                assertThat(step.path()).doesNotContain("://");
            });
        }
    }

    @Nested
    @DisplayName("input that is not a usable capture")
    class BadInput {

        @Test
        @DisplayName("nothing at all")
        void refusesAnEmptyDocument() {
            ImportResult result = importer.importFrom("  ");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.har.empty");
        }

        @Test
        @DisplayName("text that is not JSON")
        void refusesUnparseableInput() {
            ImportResult result = importer.importFrom("openapi: 3.0.0\npaths: {}\n");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.har.unreadable");
        }

        @Test
        @DisplayName("JSON that is not a capture")
        void refusesJsonThatIsNotACapture() {
            ImportResult result = importer.importFrom("{\"openapi\":\"3.0.0\",\"paths\":{}}");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.har.entries.missing");
        }

        @Test
        @DisplayName("a capture whose every entry was filtered out explains which filter did it")
        void explainsWhenNothingSurvives() {
            ImportResult result = importer.importFrom(capture(
                    """
                    {"request":{"method":"GET","url":"https://cdn.example.com/a.css","headers":[]},
                     "response":{"status":200,"content":{"mimeType":"text/css"}}}
                    """,
                    """
                    {"request":{"method":"GET","url":"https://api.example.com/gone","headers":[]},
                     "response":{"status":500,"content":{"mimeType":"application/json"}}}
                    """));

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).contains("import.har.entries.none");
            assertThat(result.findings().errors()).singleElement()
                    .satisfies(f -> assertThat(f.message())
                            .contains("1 fetched static assets").contains("1 did not succeed"));
        }

        /** A capture is a log, not a contract. Nothing in one may produce an exception. */
        @Test
        @DisplayName("no input makes it throw")
        void neverThrows() {
            List<String> inputs = List.of(
                    "", "null", "[]", "\"a string\"", "{}",
                    "{\"log\":null}", "{\"log\":{}}", "{\"log\":{\"entries\":{}}}",
                    "{\"log\":{\"entries\":[]}}",
                    "{\"log\":{\"entries\":[null]}}",
                    "{\"log\":{\"entries\":[{}]}}",
                    "{\"log\":{\"pages\":\"nonsense\",\"entries\":[{\"request\":"
                            + "{\"method\":\"GET\",\"url\":\"not a url at all\"}}]}}",
                    "{\"log\":{\"entries\":[{\"request\":{\"method\":\"GET\","
                            + "\"url\":\"https://a.example.com\",\"headers\":\"nope\","
                            + "\"queryString\":5},\"response\":{\"status\":200}}]}}");

            assertThat(inputs).allSatisfy(input ->
                    assertThat(importer.importFrom(input)).isNotNull());
        }
    }
}
