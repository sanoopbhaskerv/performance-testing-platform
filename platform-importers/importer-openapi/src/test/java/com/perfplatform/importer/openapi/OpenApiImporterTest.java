package com.perfplatform.importer.openapi;

import com.perfplatform.domain.importer.SourceImporter.ImportResult;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI importer.
 *
 * <p>Two things are being protected. The obvious one is that a published document becomes usable
 * draft scenarios. The one that matters more is that it becomes an <em>honest</em> draft: an
 * OpenAPI document describes every operation an API has and nothing about which of them matter or
 * what data they need, and a draft that filled those gaps with invented values would look ready and
 * measure the target rejecting requests.
 */
class OpenApiImporterTest {

    private final OpenApiImporter importer = new OpenApiImporter();

    private static List<String> codes(ImportResult result) {
        return result.findings().findings().stream().map(f -> f.code()).toList();
    }

    private static Optional<ScenarioSpec> named(ImportResult result, String name) {
        return result.scenarios().stream().filter(s -> s.name().equals(name)).findFirst();
    }

    private static ScenarioSpec scenario(ImportResult result, String name) {
        return named(result, name).orElseThrow(
                () -> new AssertionError("no scenario named '" + name + "' in "
                        + result.scenarios().stream().map(ScenarioSpec::name).toList()));
    }

    /** A small but complete OpenAPI 3 document, used by most of the reading tests. */
    private static final String ORDERS_API = """
            openapi: 3.0.3
            info:
              title: Orders
              version: "1.0"
            servers:
              - url: https://api.example.com/v1
            paths:
              /orders:
                get:
                  operationId: listOrders
                  parameters:
                    - name: status
                      in: query
                      required: true
                      schema:
                        type: string
                        enum: [open, closed]
                    - name: page
                      in: query
                      schema:
                        type: integer
                        default: 1
                    - name: cursor
                      in: query
                      schema:
                        type: string
                    - name: Accept
                      in: header
                      schema:
                        type: string
                        example: application/json
                post:
                  operationId: createOrder
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                        example:
                          sku: ABC-1
                          quantity: 2
              /orders/{orderId}:
                get:
                  operationId: getOrder
                  parameters:
                    - name: orderId
                      in: path
                      required: true
                      schema:
                        type: string
                        example: "12345"
            """;

    /**
     * Every test in this file lives in a {@code @Nested} class, by convention rather than necessity.
     *
     * <p>An earlier note here claimed a top-level {@code @Test} alongside {@code @Nested} classes is
     * silently not executed, because Surefire prints {@code Tests run: 0} for the outer class. That
     * is a reporting artefact of Surefire 3.1.2, not lost coverage: the outer class's tests are
     * filed into the last nested class's report file while keeping their correct {@code classname},
     * and they do run. Mixing the two is safe. See HANDOFF.md §3h for the evidence.
     */
    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("it declares itself the OpenAPI importer")
        void declaresItsSourceType() {
            assertThat(importer.sourceType()).isEqualTo(SourceType.OPENAPI);
        }
    }

    @Nested
    @DisplayName("reading the document")
    class Reading {

        @Test
        @DisplayName("every operation becomes a scenario carrying its method and path")
        void importsOperations() {
            ImportResult result = importer.importFrom(ORDERS_API);

            assertThat(result.scenarios()).hasSize(3);
            assertThat(scenario(result, "list-orders").methodOrDefault()).isEqualTo("GET");
            assertThat(scenario(result, "create-order").methodOrDefault()).isEqualTo("POST");
            assertThat(scenario(result, "get-order").methodOrDefault()).isEqualTo("GET");
        }

        /**
         * CLAUDE.md §23 requires it, and this is what lets a run's results be matched back to the
         * operation in the document. The name cannot carry it: names must be short lowercase
         * identifiers because they reach artifact object keys and Kubernetes labels.
         */
        @Test
        @DisplayName("operationId is preserved verbatim, and the name is its kebab-case form")
        void preservesOperationId() {
            ImportResult result = importer.importFrom(ORDERS_API);

            ScenarioSpec listOrders = scenario(result, "list-orders");
            assertThat(listOrders.operationId()).isEqualTo("listOrders");
            assertThat(scenario(result, "create-order").operationId()).isEqualTo("createOrder");
        }

        @Test
        @DisplayName("the server URL's path prefix is kept and its host is dropped")
        void keepsBasePathAndDropsHost() {
            ImportResult result = importer.importFrom(ORDERS_API);

            assertThat(scenario(result, "list-orders").path()).isEqualTo("/v1/orders");
            assertThat(codes(result)).contains("import.openapi.host.dropped");
        }

        @Test
        @DisplayName("a path parameter is filled in from the example the document states")
        void substitutesPathParameters() {
            assertThat(scenario(importer.importFrom(ORDERS_API), "get-order").path())
                    .isEqualTo("/v1/orders/12345");
        }

        @Test
        @DisplayName("query values come from example, default and enum; an optional one without a "
                + "stated value is simply omitted")
        void importsStatedQueryValues() {
            ScenarioSpec listOrders = scenario(importer.importFrom(ORDERS_API), "list-orders");

            assertThat(listOrders.query())
                    .containsEntry("status", "open")   // first enum member
                    .containsEntry("page", "1")        // default
                    .doesNotContainKey("cursor");      // optional, nothing stated
            assertThat(listOrders.headers()).containsEntry("Accept", "application/json");
        }

        @Test
        @DisplayName("a request body example is imported with its media type")
        void importsBodyExample() {
            ScenarioSpec createOrder = scenario(importer.importFrom(ORDERS_API), "create-order");

            assertThat(createOrder.body()).isNotNull();
            assertThat(createOrder.body().contentTypeOrDefault()).isEqualTo("application/json");
            assertThat(createOrder.body().content()).isEqualTo("{\"sku\":\"ABC-1\",\"quantity\":2}");
        }

        @Test
        @DisplayName("JSON and YAML are both read, and produce the same draft")
        void readsJsonAsWellAsYaml() {
            ImportResult json = importer.importFrom("""
                    {"openapi":"3.0.0","paths":{"/health":{"get":{"operationId":"health"}}}}
                    """);

            assertThat(json.scenarios()).hasSize(1);
            assertThat(json.scenarios().get(0).path()).isEqualTo("/health");
            assertThat(json.scenarios().get(0).operationId()).isEqualTo("health");
        }

        @Test
        @DisplayName("path-level parameters apply to every operation under that path")
        void mergesPathLevelParameters() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /tenants/{tenantId}/orders:
                        parameters:
                          - name: tenantId
                            in: path
                            required: true
                            schema: {type: string, example: acme}
                        get:
                          operationId: listTenantOrders
                        post:
                          operationId: createTenantOrder
                    """);

            assertThat(result.scenarios()).hasSize(2);
            assertThat(result.scenarios()).allSatisfy(scenario ->
                    assertThat(scenario.path()).isEqualTo("/tenants/acme/orders"));
        }

        @Test
        @DisplayName("a local $ref is followed")
        void followsLocalRefs() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - $ref: '#/components/parameters/Status'
                    components:
                      parameters:
                        Status:
                          name: status
                          in: query
                          required: true
                          schema: {type: string, example: open}
                    """);

            assertThat(scenario(result, "list-orders").query()).containsEntry("status", "open");
        }

        @Test
        @DisplayName("a Swagger 2.0 document is read, including basePath and its body parameter")
        void readsSwagger2() {
            ImportResult result = importer.importFrom("""
                    swagger: "2.0"
                    host: api.example.com
                    basePath: /v2
                    consumes: [application/json]
                    paths:
                      /orders:
                        post:
                          operationId: createOrder
                          parameters:
                            - name: body
                              in: body
                              required: true
                              schema:
                                type: object
                                example: {sku: ABC}
                            - name: dryRun
                              in: query
                              type: boolean
                              default: false
                    """);

            ScenarioSpec createOrder = scenario(result, "create-order");
            assertThat(createOrder.path()).isEqualTo("/v2/orders");
            assertThat(createOrder.body().content()).isEqualTo("{\"sku\":\"ABC\"}");
            assertThat(createOrder.body().contentTypeOrDefault()).isEqualTo("application/json");
            assertThat(createOrder.query()).containsEntry("dryRun", "false");
            assertThat(codes(result)).contains("import.openapi.host.dropped");
        }
    }

    /**
     * The rule the whole importer is built around: a schema describes a shape, not a value.
     *
     * <p>Inventing one produces a test that sends requests the target rejects, at full load, and
     * reports the rejection latency as the API's performance. Every gap is left visible instead.
     */
    @Nested
    @DisplayName("it does not invent business data")
    class NoInvention {

        @Test
        @DisplayName("a body described only by a schema is not generated, and the gap is reported")
        void doesNotGenerateABodyFromASchema() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        post:
                          operationId: createOrder
                          requestBody:
                            required: true
                            content:
                              application/json:
                                schema:
                                  type: object
                                  required: [sku, quantity]
                                  properties:
                                    sku: {type: string}
                                    quantity: {type: integer}
                    """);

            assertThat(scenario(result, "create-order").body()).isNull();
            assertThat(codes(result)).contains("import.openapi.body.missing");
        }

        @Test
        @DisplayName("a required query parameter with no stated value is reported, not filled in")
        void doesNotInventARequiredQueryValue() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - name: customerId
                              in: query
                              required: true
                              schema: {type: string, format: uuid}
                    """);

            assertThat(scenario(result, "list-orders").query()).isEmpty();
            assertThat(codes(result)).contains("import.openapi.value.missing");
        }

        /**
         * The sharpest case. A path sent with {@code {orderId}} still in it returns 404 for every
         * request, and a run made of 404s reports fast, healthy-looking latency for a target that
         * did no work — so this is an error, not a warning, even though the scenario is still
         * returned for someone to complete.
         */
        @Test
        @DisplayName("an unfillable path parameter leaves the placeholder and is an error")
        void doesNotInventAPathParameter() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders/{orderId}:
                        get:
                          operationId: getOrder
                          parameters:
                            - name: orderId
                              in: path
                              required: true
                              schema: {type: string}
                    """);

            assertThat(scenario(result, "get-order").path()).isEqualTo("/orders/{orderId}");
            assertThat(result.findings().errors())
                    .extracting(f -> f.code())
                    .contains("import.openapi.path.parameter.unresolved");
        }

        @Test
        @DisplayName("values taken from the document are reported as such, not presented as fact")
        void reportsWhereValuesCameFrom() {
            assertThat(codes(importer.importFrom(ORDERS_API)))
                    .contains("import.openapi.values.fromDocument");
        }

        @Test
        @DisplayName("equal weights are stated to be a starting point, not a traffic mix")
        void reportsThatWeightsAreEqual() {
            assertThat(codes(importer.importFrom(ORDERS_API)))
                    .contains("import.openapi.weights.equal");
        }

        @Test
        @DisplayName("an imported body is reported as fixed for every virtual user")
        void reportsThatBodiesAreStatic() {
            assertThat(codes(importer.importFrom(ORDERS_API)))
                    .contains("import.openapi.body.static");
        }
    }

    @Nested
    @DisplayName("credentials are not imported")
    class Credentials {

        @Test
        @DisplayName("a parameter named as authentication is refused by name")
        void refusesCredentialParametersByName() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - name: Authorization
                              in: header
                              required: true
                              schema: {type: string, example: "Bearer abc123"}
                            - name: X-Api-Key
                              in: query
                              required: true
                              schema: {type: string, example: notatokenshape}
                    """);

            ScenarioSpec listOrders = scenario(result, "list-orders");
            assertThat(listOrders.headers()).isEmpty();
            assertThat(listOrders.query()).isEmpty();
            assertThat(codes(result)).contains("import.openapi.parameter.credential");
        }

        @Test
        @DisplayName("a token-shaped example under an innocuous name is refused by shape")
        void refusesCredentialShapedExamples() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - name: X-Session
                              in: header
                              required: true
                              schema:
                                type: string
                                example: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl
                    """);

            assertThat(scenario(result, "list-orders").headers()).isEmpty();
            assertThat(codes(result)).contains("import.openapi.parameter.credential");
        }

        /** A finding is returned over HTTP and written to logs; it must not carry the value. */
        @Test
        @DisplayName("no finding ever echoes the value it refused")
        void neverEchoesARefusedValue() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - name: X-Session
                              in: header
                              required: true
                              schema:
                                type: string
                                example: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl
                    """);

            assertThat(result.findings().findings())
                    .allSatisfy(finding -> assertThat(finding.message() + finding.remediation())
                            .doesNotContain("eyJhbGciOiJIUzI1NiJ9"));
        }

        @Test
        @DisplayName("a cookie parameter is dropped and reported")
        void dropsCookieParameters() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - name: session
                              in: cookie
                              schema: {type: string, example: abc}
                    """);

            assertThat(scenario(result, "list-orders").headers()).isEmpty();
            assertThat(codes(result)).contains("import.openapi.parameter.cookie");
        }

        @Test
        @DisplayName("a declared security requirement is reported so it is replaced, not discovered")
        void reportsDeclaredSecurity() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    security:
                      - bearerAuth: []
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                    """);

            assertThat(codes(result)).contains("import.openapi.security.declared");
        }
    }

    /**
     * The document arrives over HTTP from whoever uploaded it, so it is untrusted input.
     */
    @Nested
    @DisplayName("an uploaded document cannot make the platform act")
    class Safety {

        /**
         * Following a {@code $ref} to a URL would have the control plane fetch an address named by
         * an uploaded document, using the platform's own network position. It is reported instead.
         */
        @Test
        @DisplayName("an external $ref is not followed, and its absence is reported")
        void doesNotFollowExternalRefs() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - $ref: 'http://169.254.169.254/latest/meta-data#/status'
                            - $ref: './common.yaml#/components/parameters/Page'
                    """);

            assertThat(scenario(result, "list-orders").query()).isEmpty();
            assertThat(codes(result)).contains("import.openapi.ref.external");
        }

        @Test
        @DisplayName("a reference cycle terminates rather than looping")
        void terminatesOnAReferenceCycle() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /orders:
                        get:
                          operationId: listOrders
                          parameters:
                            - $ref: '#/components/parameters/Loop'
                    components:
                      parameters:
                        Loop:
                          $ref: '#/components/parameters/Loop'
                    """);

            assertThat(result.scenarios()).hasSize(1);
            assertThat(scenario(result, "list-orders").query()).isEmpty();
        }

        @Test
        @DisplayName("a path is never a full URL, so it cannot name its own host")
        void neverProducesAnAbsolutePath() {
            ImportResult result = importer.importFrom(ORDERS_API);

            assertThat(result.scenarios()).allSatisfy(scenario -> {
                assertThat(scenario.path()).startsWith("/");
                assertThat(scenario.path()).doesNotContain("://");
            });
        }
    }

    @Nested
    @DisplayName("the shape of the draft")
    class Draft {

        @Test
        @DisplayName("weights are equal shares summing to 100")
        void weightsSumToOneHundred() {
            ImportResult result = importer.importFrom(ORDERS_API);

            assertThat(result.scenarios()).extracting(ScenarioSpec::weight)
                    .containsExactly(34, 33, 33);
        }

        @Test
        @DisplayName("scenario names are unique even when operationIds collide once shortened")
        void namesAreUnique() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /a:
                        get: {operationId: listOrders}
                      /b:
                        get: {operationId: list_orders}
                      /c:
                        get: {}
                      /d:
                        get: {}
                    """);

            assertThat(result.scenarios()).extracting(ScenarioSpec::name)
                    .doesNotHaveDuplicates()
                    .contains("list-orders", "list-orders-2", "get-c", "get-d");
        }

        @Test
        @DisplayName("TRACE is skipped, because the specification does not support it")
        void skipsTrace() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /debug:
                        trace: {operationId: traceDebug}
                        get: {operationId: getDebug}
                    """);

            assertThat(result.scenarios()).extracting(ScenarioSpec::operationId)
                    .containsExactly("getDebug");
            assertThat(codes(result)).contains("import.openapi.method.trace");
        }

        @Test
        @DisplayName("a body declared on a GET is not imported, and the reason is given")
        void dropsABodyOnGet() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /search:
                        get:
                          operationId: search
                          requestBody:
                            content:
                              application/json:
                                example: {q: shoes}
                    """);

            assertThat(scenario(result, "search").body()).isNull();
            assertThat(codes(result)).contains("import.openapi.body.onMethodWithoutBody");
        }

        @Test
        @DisplayName("a deprecated operation is imported and flagged")
        void flagsDeprecatedOperations() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /legacy:
                        get: {operationId: legacyList, deprecated: true}
                    """);

            assertThat(result.scenarios()).hasSize(1);
            assertThat(codes(result)).contains("import.openapi.operation.deprecated");
        }

        @Test
        @DisplayName("a very large document is truncated, and says so")
        void truncatesVeryLargeDocuments() {
            StringBuilder document = new StringBuilder("openapi: 3.0.0\npaths:\n");
            for (int i = 0; i < OpenApiImporter.MAX_OPERATIONS + 25; i++) {
                document.append("  /resource").append(i).append(":\n")
                        .append("    get: {operationId: get").append(i).append("}\n");
            }

            ImportResult result = importer.importFrom(document.toString());

            assertThat(result.scenarios()).hasSize(OpenApiImporter.MAX_OPERATIONS);
            assertThat(codes(result)).contains("import.openapi.operations.truncated");
            // Above a hundred operations an equal share rounds to zero, which the specification
            // refuses; a weight of 1 each keeps every scenario runnable.
            assertThat(result.scenarios()).allSatisfy(
                    scenario -> assertThat(scenario.weight()).isEqualTo(1));
        }
    }

    @Nested
    @DisplayName("input that is not an OpenAPI document")
    class BadInput {

        @Test
        @DisplayName("nothing at all")
        void refusesAnEmptyDocument() {
            ImportResult result = importer.importFrom("   ");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.openapi.empty");
        }

        @Test
        @DisplayName("text that is not YAML or JSON")
        void refusesUnparseableInput() {
            ImportResult result = importer.importFrom("curl https://api.example.com/orders");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.openapi.unreadable");
        }

        @Test
        @DisplayName("valid YAML that is not an API document")
        void refusesYamlThatIsNotAnApiDocument() {
            ImportResult result = importer.importFrom("apiVersion: v1\nkind: ConfigMap\n");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.openapi.unreadable");
        }

        @Test
        @DisplayName("an API document declaring no paths")
        void refusesADocumentWithNoPaths() {
            ImportResult result = importer.importFrom("openapi: 3.0.0\ninfo: {title: Empty}\n");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.openapi.paths.missing");
        }

        @Test
        @DisplayName("paths declaring only unsupported operations")
        void refusesADocumentWithNoSupportedOperations() {
            ImportResult result = importer.importFrom("""
                    openapi: 3.0.0
                    paths:
                      /debug:
                        trace: {operationId: traceDebug}
                    """);

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).contains("import.openapi.operations.none");
        }

        /** An importer's contract: a malformed document is an ordinary event, never an exception. */
        @Test
        @DisplayName("no input makes it throw")
        void neverThrows() {
            List<String> inputs = List.of("", "null", "[]", "\"just a string\"", "{}",
                    "openapi: 3.0.0\npaths: []\n",
                    "openapi: 3.0.0\npaths:\n  /a: null\n",
                    "openapi: 3.0.0\npaths:\n  /a:\n    get: \"not an object\"\n",
                    "swagger: \"2.0\"\npaths:\n  /a:\n    post:\n      parameters: \"nonsense\"\n",
                    "openapi: 3.0.0\npaths:\n  /a/{x}:\n    get:\n      parameters:\n"
                            + "        - {name: x, in: path}\n");

            assertThat(inputs).allSatisfy(input ->
                    assertThat(importer.importFrom(input)).isNotNull());
        }
    }
}
