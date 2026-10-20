package com.perfplatform.importer.postman;

import com.perfplatform.domain.importer.SourceImporter.ImportResult;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Postman importer.
 *
 * <p>A collection is the friendliest source this platform accepts — somebody already named these
 * requests and arranged them into folders — and it hides two things the others do not. It carries
 * <b>executable code</b>, which is never run and has to be reported so nobody discovers at run time
 * that the sign-in a script performed is missing. And it carries <b>credentials that nobody thinks
 * of as sensitive</b>: an exported collection ships its variable values, and a variable named
 * {@code accessToken} usually holds a working one.
 */
class PostmanImporterTest {

    private final PostmanImporter importer = new PostmanImporter();

    private static List<String> codes(ImportResult result) {
        return result.findings().findings().stream().map(f -> f.code()).toList();
    }

    private static List<String> names(ImportResult result) {
        return result.scenarios().stream().map(ScenarioSpec::name).toList();
    }

    private static ScenarioSpec scenario(ImportResult result, String name) {
        return result.scenarios().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no scenario named '" + name + "' in " + names(result)));
    }

    /** A collection shaped like the ones teams actually export. */
    private static final String ORDERS_COLLECTION = """
            {
              "info": {"name": "Orders API",
                       "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
              "auth": {"type": "bearer",
                       "bearer": [{"key": "token", "value": "{{accessToken}}"}]},
              "variable": [
                {"key": "baseUrl", "value": "https://api.example.com/v1"},
                {"key": "accessToken", "value": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln"},
                {"key": "status", "value": "open"}
              ],
              "item": [
                {
                  "name": "Orders",
                  "item": [
                    {
                      "name": "List all orders",
                      "request": {
                        "method": "GET",
                        "header": [{"key": "Accept", "value": "application/json"},
                                   {"key": "Authorization", "value": "Bearer {{accessToken}}"},
                                   {"key": "X-Debug", "value": "1", "disabled": true}],
                        "url": {
                          "raw": "{{baseUrl}}/orders?status={{status}}",
                          "host": ["{{baseUrl}}"],
                          "path": ["orders"],
                          "query": [{"key": "status", "value": "{{status}}"},
                                    {"key": "verbose", "value": "true", "disabled": true}]
                        }
                      }
                    },
                    {
                      "name": "Create order",
                      "event": [{"listen": "prerequest",
                                 "script": {"exec": ["pm.environment.set('nonce', Date.now())"]}}],
                      "request": {
                        "method": "POST",
                        "header": [{"key": "Content-Type", "value": "application/json"}],
                        "url": {"raw": "{{baseUrl}}/orders",
                                "host": ["{{baseUrl}}"], "path": ["orders"]},
                        "body": {"mode": "raw", "raw": "{\\"sku\\":\\"ABC-1\\",\\"quantity\\":2}",
                                 "options": {"raw": {"language": "json"}}}
                      }
                    }
                  ]
                },
                {
                  "name": "Sign in",
                  "request": {
                    "method": "POST",
                    "url": {"raw": "{{baseUrl}}/auth/login",
                            "host": ["{{baseUrl}}"], "path": ["auth", "login"]},
                    "body": {"mode": "raw",
                             "raw": "{\\"username\\":\\"a.smith\\",\\"password\\":\\"Sunflower2019!\\"}",
                             "options": {"raw": {"language": "json"}}}
                  }
                }
              ]
            }
            """;

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("it declares itself the Postman importer")
        void declaresItsSourceType() {
            assertThat(importer.sourceType()).isEqualTo(SourceType.POSTMAN);
        }
    }

    @Nested
    @DisplayName("reading the collection")
    class Reading {

        /**
         * The reason a collection is worth importing at all: somebody already wrote down what each
         * request is for, and which flow it belongs to.
         */
        @Test
        @DisplayName("names come from the author, prefixed by the folders they sit in")
        void namesScenariosAfterTheirAuthorAndFolder() {
            assertThat(names(importer.importFrom(ORDERS_COLLECTION)))
                    .containsExactly("orders-list-all-orders", "orders-create-order", "sign-in");
        }

        @Test
        @DisplayName("method, path and query survive; the host does not")
        void importsTheRequestWithoutItsHost() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);
            ScenarioSpec list = scenario(result, "orders-list-all-orders");

            assertThat(list.methodOrDefault()).isEqualTo("GET");
            assertThat(list.path()).isEqualTo("/v1/orders");
            assertThat(codes(result)).contains("import.postman.host.dropped");
        }

        /**
         * {@code {{baseUrl}}} usually resolves to a URL with a path on it, and that path is part of
         * the request rather than part of the address.
         */
        @Test
        @DisplayName("a base-URL variable's path prefix is kept while its host is dropped")
        void keepsThePathPrefixFromABaseUrlVariable() {
            assertThat(importer.importFrom(ORDERS_COLLECTION).scenarios())
                    .allSatisfy(scenario -> assertThat(scenario.path()).startsWith("/v1/"));
        }

        @Test
        @DisplayName("a variable is substituted into a query value")
        void substitutesVariablesIntoQueryValues() {
            assertThat(scenario(importer.importFrom(ORDERS_COLLECTION), "orders-list-all-orders")
                    .query()).containsEntry("status", "open");
        }

        @Test
        @DisplayName("a raw JSON body is imported with the media type its language implies")
        void importsARawBody() {
            ScenarioSpec create = scenario(importer.importFrom(ORDERS_COLLECTION),
                    "orders-create-order");

            assertThat(create.body()).isNotNull();
            assertThat(create.body().content()).isEqualTo("{\"sku\":\"ABC-1\",\"quantity\":2}");
            assertThat(create.body().contentTypeOrDefault()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("a url-encoded body is assembled from its enabled fields")
        void importsAUrlEncodedBody() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Search","request":{"method":"POST",
                      "url":"https://api.example.com/search",
                      "body":{"mode":"urlencoded","urlencoded":[
                        {"key":"q","value":"red shoes"},
                        {"key":"page","value":"2"},
                        {"key":"debug","value":"1","disabled":true}]}}}
                    """));

            ScenarioSpec search = scenario(result, "search");
            assertThat(search.body().content()).isEqualTo("q=red+shoes&page=2");
            assertThat(search.body().contentTypeOrDefault())
                    .isEqualTo("application/x-www-form-urlencoded");
        }

        @Test
        @DisplayName("a GraphQL body becomes the JSON body a server actually receives")
        void importsAGraphqlBody() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Orders query","request":{"method":"POST",
                      "url":"https://api.example.com/graphql",
                      "body":{"mode":"graphql","graphql":{
                        "query":"query { orders { id } }",
                        "variables":"{\\"first\\":10}"}}}}
                    """));

            assertThat(scenario(result, "orders-query").body().content())
                    .isEqualTo("{\"query\":\"query { orders { id } }\",\"variables\":{\"first\":10}}");
        }

        @Test
        @DisplayName("a request written as a bare URL string is a GET")
        void readsAShorthandRequest() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Health","request":"https://api.example.com/health"}
                    """));

            assertThat(scenario(result, "health").methodOrDefault()).isEqualTo("GET");
            assertThat(scenario(result, "health").path()).isEqualTo("/health");
        }

        @Test
        @DisplayName("nested folders become a nested name prefix")
        void nestsFolderNames() {
            ImportResult result = importer.importFrom("""
                    {"item":[{"name":"Admin","item":[{"name":"Users","item":[
                      {"name":"List users","request":{"method":"GET",
                        "url":"https://api.example.com/admin/users"}}]}]}]}
                    """);

            assertThat(names(result)).containsExactly("admin-users-list-users");
        }
    }

    /**
     * CLAUDE.md §23: flag scripts, never execute them.
     *
     * <p>The control plane does not run code that arrived in a document, and it does not summarise
     * it either — a description of an unevaluated script would be a guess presented as fact.
     */
    @Nested
    @DisplayName("scripts are flagged, never executed")
    class Scripts {

        @Test
        @DisplayName("a pre-request script is reported against the request it belongs to")
        void reportsPreRequestScripts() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(codes(result)).contains("import.postman.script.notExecuted");
            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.script.notExecuted"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("Create order"));
        }

        @Test
        @DisplayName("collection-level and folder-level scripts are reported too")
        void reportsScriptsAtEveryLevel() {
            ImportResult result = importer.importFrom("""
                    {"event":[{"listen":"prerequest","script":{"exec":["signIn()"]}}],
                     "item":[{"name":"Orders",
                       "event":[{"listen":"test","script":{"exec":["pm.test('ok', ()=>{})"]}}],
                       "item":[{"name":"List","request":{"method":"GET",
                         "url":"https://api.example.com/orders"}}]}]}
                    """);

            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.script.notExecuted"))
                    .hasSize(2)
                    .satisfies(all -> assertThat(all.toString())
                            .contains("the collection").contains("folder 'Orders'"));
        }

        /**
         * The failure this warning exists to prevent: a collection whose scripts do the sign-in
         * imports perfectly and then fails on every request.
         */
        @Test
        @DisplayName("the summary says why an import can look clean and still fail entirely")
        void summarisesTheConsequence() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.scripts.summary"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.remediation()).contains("fail at run time"));
        }

        /** An empty event block is not a script, and reporting it would be noise. */
        @Test
        @DisplayName("an empty script block is not reported")
        void ignoresEmptyScripts() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"List","event":[{"listen":"test","script":{"exec":[]}}],
                     "request":{"method":"GET","url":"https://api.example.com/orders"}}
                    """));

            assertThat(codes(result)).doesNotContain("import.postman.script.notExecuted");
        }

        /** Nothing in a collection is evaluated — the script text never reaches a scenario. */
        @Test
        @DisplayName("no script's text appears anywhere in the draft")
        void neverCarriesScriptText() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(result.scenarios().toString()).doesNotContain("pm.environment");
            assertThat(result.findings().toString()).doesNotContain("Date.now");
        }
    }

    @Nested
    @DisplayName("a shared collection cannot carry credentials into the platform")
    class Credentials {

        /**
         * The quiet one. Everyone knows a HAR is sensitive; nobody thinks that about a collection
         * they send a teammate — and the export ships its variable values.
         */
        @Test
        @DisplayName("a variable holding a credential is never substituted, and is named")
        void refusesCredentialVariables() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(result.scenarios().toString()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.variable.credential"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("accessToken"));
        }

        @Test
        @DisplayName("no finding echoes the credential it refused")
        void neverEchoesARefusedValue() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(result.findings().findings()).allSatisfy(finding ->
                    assertThat(finding.message() + finding.remediation())
                            .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                            .doesNotContain("Sunflower2019"));
        }

        @Test
        @DisplayName("an Authorization header is not imported")
        void refusesAuthorizationHeaders() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(scenario(result, "orders-list-all-orders").headers())
                    .doesNotContainKey("Authorization")
                    .containsEntry("Accept", "application/json");
            assertThat(codes(result)).contains("import.postman.header.credential");
        }

        @Test
        @DisplayName("a declared auth block is reported and never imported")
        void reportsAuthBlocks() {
            assertThat(codes(importer.importFrom(ORDERS_COLLECTION)))
                    .contains("import.postman.auth.declared");
        }

        @Test
        @DisplayName("'noauth' is not an auth block")
        void ignoresNoAuth() {
            ImportResult result = importer.importFrom("""
                    {"auth":{"type":"noauth"},
                     "item":[{"name":"List","request":{"method":"GET",
                       "url":"https://api.example.com/orders"}}]}
                    """);

            assertThat(codes(result)).doesNotContain("import.postman.auth.declared");
        }

        /**
         * A saved sign-in request keeps whatever value made it work. No check on the value would
         * recognise {@code Sunflower2019!}; the check has to be on the field's name.
         */
        @Test
        @DisplayName("a saved sign-in body is refused, and the finding names the field")
        void refusesASavedPassword() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(scenario(result, "sign-in").body()).isNull();
            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.body.credential"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("password"));
        }

        @Test
        @DisplayName("a credential in a url-encoded body is refused before it is encoded")
        void refusesAFormEncodedCredential() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Token","request":{"method":"POST",
                      "url":"https://api.example.com/oauth/token",
                      "body":{"mode":"urlencoded","urlencoded":[
                        {"key":"grant_type","value":"client_credentials"},
                        {"key":"client_secret","value":"s3cr3t"}]}}}
                    """));

            assertThat(scenario(result, "token").body()).isNull();
            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.body.credential"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("client_secret"));
        }

        @Test
        @DisplayName("a body carrying personal data is refused")
        void refusesPersonalData() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Apply","request":{"method":"POST",
                      "url":"https://api.example.com/applications",
                      "body":{"mode":"raw","raw":"{\\"name\\":\\"A Smith\\",\\"ssn\\":\\"123-45-6789\\"}",
                              "options":{"raw":{"language":"json"}}}}}
                    """));

            assertThat(scenario(result, "apply").body()).isNull();
            assertThat(codes(result)).contains("import.postman.body.personalData");
        }

        @Test
        @DisplayName("a credential in a query string is dropped")
        void refusesCredentialQueryParameters() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"List","request":{"method":"GET",
                      "url":{"raw":"https://api.example.com/orders",
                             "host":["api.example.com"],"path":["orders"],
                             "query":[{"key":"x-api-key","value":"abc"},
                                      {"key":"page","value":"2"}]}}}
                    """));

            assertThat(scenario(result, "list").query())
                    .doesNotContainKey("x-api-key").containsEntry("page", "2");
            assertThat(codes(result)).contains("import.postman.query.credential");
        }
    }

    @Nested
    @DisplayName("variables that cannot be resolved")
    class Variables {

        /**
         * The sharpest gap. A path sent with {@code {{orderId}}} still in it 404s on every request,
         * and a run made of 404s reports fast, healthy-looking latency for a target that did no
         * work.
         */
        @Test
        @DisplayName("an unresolved placeholder in a path is an error")
        void unresolvedPathVariableIsAnError() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Get order","request":{"method":"GET",
                      "url":{"raw":"https://api.example.com/orders/{{orderId}}",
                             "host":["api.example.com"],"path":["orders","{{orderId}}"]}}}
                    """));

            assertThat(scenario(result, "get-order").path()).isEqualTo("/orders/{{orderId}}");
            assertThat(result.findings().errors()).extracting(f -> f.code())
                    .contains("import.postman.path.unresolved");
        }

        @Test
        @DisplayName("Postman's ':name' path syntax resolves from the request's own variable list")
        void resolvesColonPathVariables() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Get order","request":{"method":"GET",
                      "url":{"raw":"https://api.example.com/orders/:orderId",
                             "host":["api.example.com"],"path":["orders",":orderId"],
                             "variable":[{"key":"orderId","value":"12345"}]}}}
                    """));

            assertThat(scenario(result, "get-order").path()).isEqualTo("/orders/12345");
            assertThat(result.findings().errors()).isEmpty();
        }

        @Test
        @DisplayName("a ':name' with no stated value is an error, like any other unresolved path")
        void unresolvedColonVariableIsAnError() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Get order","request":{"method":"GET",
                      "url":{"raw":"https://api.example.com/orders/:orderId",
                             "host":["api.example.com"],"path":["orders",":orderId"]}}}
                    """));

            assertThat(result.findings().errors()).extracting(f -> f.code())
                    .contains("import.postman.path.unresolved");
        }

        @Test
        @DisplayName("a variable the collection never defines is reported as unknown, not invented")
        void reportsUnknownVariables() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"List","request":{"method":"GET",
                      "url":{"raw":"https://api.example.com/orders",
                             "host":["api.example.com"],"path":["orders"],
                             "query":[{"key":"tenant","value":"{{tenantId}}"}]}}}
                    """));

            assertThat(scenario(result, "list").query()).containsEntry("tenant", "{{tenantId}}");
            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.variable.unknown"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("tenantId"));
        }

        @Test
        @DisplayName("a Postman-generated variable is reported as one, and a dataset offered instead")
        void reportsDynamicVariables() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"List","request":{"method":"GET",
                      "url":{"raw":"https://api.example.com/orders",
                             "host":["api.example.com"],"path":["orders"],
                             "query":[{"key":"nonce","value":"{{$guid}}"}]}}}
                    """));

            assertThat(result.findings().findings())
                    .filteredOn(f -> f.code().equals("import.postman.variable.dynamic"))
                    .singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("$guid"));
        }
    }

    @Nested
    @DisplayName("the shape of the draft")
    class Draft {

        @Test
        @DisplayName("weights are equal shares summing to 100, and say so")
        void weightsAreEqualAndExplained() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(result.scenarios()).extracting(ScenarioSpec::weight)
                    .containsExactly(34, 33, 33);
            assertThat(codes(result)).contains("import.postman.weights.equal");
        }

        @Test
        @DisplayName("switched-off headers, parameters and fields are skipped and counted")
        void skipsDisabledItems() {
            ImportResult result = importer.importFrom(ORDERS_COLLECTION);

            assertThat(scenario(result, "orders-list-all-orders").headers())
                    .doesNotContainKey("X-Debug");
            assertThat(scenario(result, "orders-list-all-orders").query())
                    .doesNotContainKey("verbose");
            assertThat(codes(result)).contains("import.postman.disabled.skipped");
        }

        @Test
        @DisplayName("a form-data or file body is refused: a collection stores a path, not contents")
        void refusesFileBodies() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Upload","request":{"method":"POST",
                      "url":"https://api.example.com/documents",
                      "body":{"mode":"formdata","formdata":[
                        {"key":"file","type":"file","src":"/Users/someone/report.pdf"}]}}}
                    """));

            assertThat(scenario(result, "upload").body()).isNull();
            assertThat(codes(result)).contains("import.postman.body.unsupported");
        }

        @Test
        @DisplayName("a body declared on a GET is not imported")
        void dropsABodyOnGet() {
            ImportResult result = importer.importFrom(collection("""
                    {"name":"Search","request":{"method":"GET",
                      "url":"https://api.example.com/search",
                      "body":{"mode":"raw","raw":"{\\"q\\":\\"shoes\\"}"}}}
                    """));

            assertThat(scenario(result, "search").body()).isNull();
            assertThat(codes(result)).contains("import.postman.body.onMethodWithoutBody");
        }

        @Test
        @DisplayName("names are unique even when two folders hold the same request name")
        void namesAreUnique() {
            ImportResult result = importer.importFrom("""
                    {"item":[
                      {"name":"List","request":{"method":"GET","url":"https://api.example.com/a"}},
                      {"name":"List","request":{"method":"GET","url":"https://api.example.com/b"}}]}
                    """);

            assertThat(names(result)).containsExactly("list", "list-2");
        }

        @Test
        @DisplayName("a very large collection is truncated, and says so")
        void truncatesLargeCollections() {
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < PostmanImporter.MAX_SCENARIOS + 20; i++) {
                items.append(i > 0 ? "," : "")
                        .append("{\"name\":\"Request ").append(i)
                        .append("\",\"request\":{\"method\":\"GET\",")
                        .append("\"url\":\"https://api.example.com/r").append(i).append("\"}}");
            }

            ImportResult result = importer.importFrom("{\"item\":[" + items + "]}");

            assertThat(result.scenarios()).hasSize(PostmanImporter.MAX_SCENARIOS);
            assertThat(codes(result)).contains("import.postman.items.truncated");
        }

        @Test
        @DisplayName("a path is never a full URL, so a collection cannot name its own host")
        void neverProducesAnAbsolutePath() {
            assertThat(importer.importFrom(ORDERS_COLLECTION).scenarios()).allSatisfy(scenario -> {
                assertThat(scenario.path()).startsWith("/");
                assertThat(scenario.path()).doesNotContain("://");
            });
        }
    }

    @Nested
    @DisplayName("input that is not a usable collection")
    class BadInput {

        @Test
        @DisplayName("nothing at all")
        void refusesAnEmptyDocument() {
            ImportResult result = importer.importFrom("  ");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.postman.empty");
        }

        @Test
        @DisplayName("text that is not JSON")
        void refusesUnparseableInput() {
            ImportResult result = importer.importFrom("openapi: 3.0.0\npaths: {}\n");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.postman.unreadable");
        }

        /** Postman itself converts these; maintaining a reader for it would be the wrong trade. */
        @Test
        @DisplayName("a Collection v1 export is refused by name, with the way out")
        void refusesLegacyCollections() {
            ImportResult result = importer.importFrom("""
                    {"id":"abc","name":"Old","order":[],
                     "requests":[{"method":"GET","url":"https://api.example.com/orders"}]}
                    """);

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.postman.legacyFormat");
            assertThat(result.findings().errors()).singleElement()
                    .satisfies(f -> assertThat(f.remediation()).contains("Collection v2.1"));
        }

        @Test
        @DisplayName("JSON that is not a collection")
        void refusesJsonThatIsNotACollection() {
            ImportResult result = importer.importFrom("{\"log\":{\"entries\":[]}}");

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).containsExactly("import.postman.items.missing");
        }

        @Test
        @DisplayName("folders holding no requests")
        void refusesACollectionWithNoRequests() {
            ImportResult result = importer.importFrom("""
                    {"item":[{"name":"Empty folder","item":[]}]}
                    """);

            assertThat(result.isEmpty()).isTrue();
            assertThat(codes(result)).contains("import.postman.items.none");
        }

        @Test
        @DisplayName("a request with no URL is skipped and reported")
        void skipsRequestsWithNoUrl() {
            ImportResult result = importer.importFrom("""
                    {"item":[
                      {"name":"Broken","request":{"method":"GET"}},
                      {"name":"Fine","request":{"method":"GET","url":"https://api.example.com/ok"}}]}
                    """);

            assertThat(names(result)).containsExactly("fine");
            assertThat(codes(result)).contains("import.postman.url.missing");
        }

        /** A collection is an export, not a contract. Nothing in one may produce an exception. */
        @Test
        @DisplayName("no input makes it throw")
        void neverThrows() {
            List<String> inputs = List.of(
                    "", "null", "[]", "\"a string\"", "{}",
                    "{\"item\":[]}", "{\"item\":null}", "{\"item\":[null]}", "{\"item\":[{}]}",
                    "{\"item\":[{\"name\":\"x\",\"request\":{}}]}",
                    "{\"item\":[{\"name\":\"x\",\"request\":{\"method\":\"GET\",\"url\":{}}}]}",
                    "{\"item\":[{\"name\":\"x\",\"request\":{\"method\":\"GET\","
                            + "\"url\":\"not a url\",\"header\":\"nope\",\"body\":5}}]}",
                    "{\"variable\":\"nope\",\"item\":[{\"name\":\"x\","
                            + "\"request\":{\"method\":\"GET\",\"url\":\"{{unclosed\"}}]}",
                    "{\"item\":[{\"name\":\"x\",\"request\":{\"method\":\"POST\","
                            + "\"url\":\"https://a.example.com/x\",\"body\":{\"mode\":\"graphql\","
                            + "\"graphql\":{\"query\":\"{a}\",\"variables\":\"not json\"}}}}]}");

            assertThat(inputs).allSatisfy(input ->
                    assertThat(importer.importFrom(input)).isNotNull());
        }
    }

    /** Wraps items in the smallest valid collection around them. */
    private static String collection(String... items) {
        return "{\"item\":[" + String.join(",", items) + "]}";
    }
}
