package com.perfplatform.api.v2.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.api.v2.dto.ValidatorResponse;
import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.AssertionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSource;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ExtractionSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.domain.validation.ValidationResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpecificationDryRunServiceTest {

    private static final TeamId TEAM = TeamId.generate();
    private static final CallerContext CALLER = new CallerContext("alice@example.com", Set.of(TEAM),
            Set.of(Scope.RUNS_EXECUTE), false);

    private HttpServer server;
    private SpecificationDryRunService service;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> respond(exchange, 200,
                "application/json", "{\"token\":\"abc123\"}"));
        server.createContext("/orders", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            int status = "session=abc123".equals(query) ? 201 : 401;
            respond(exchange, status, "application/json", "{\"status\":\"created\"}");
        });
        server.start();

        TargetAddress target = new TargetAddress("http", "127.0.0.1", server.getAddress().getPort());
        TargetResolver targets = (teamId, applicationName, environment) -> Optional.of(target);
        service = new SpecificationDryRunService(teamDirectory(), targets, HttpClient.newHttpClient(),
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a validator pass carries an extracted value into the later request")
    void carriesCorrelationForward() {
        ValidatorResponse response = service.validate(specification("$.token", 201),
                ValidationResult.valid(), CALLER);

        assertThat(response.valid()).isTrue();
        assertThat(response.journeys()).hasSize(1);
        assertThat(response.journeys().get(0).steps()).hasSize(2);
        assertThat(response.journeys().get(0).steps().get(0).extractions().get(0).resolved()).isTrue();
        assertThat(response.journeys().get(0).steps().get(1).request().url())
                .endsWith("/orders?session=abc123");
        assertThat(response.journeys().get(0).steps().get(1).assertions().get(0).passed()).isTrue();
    }

    @Test
    @DisplayName("a broken extraction names the offending step and does not call later steps")
    void brokenExtractionFailsTheJourney() {
        ValidatorResponse response = service.validate(specification("$.missing", 201),
                ValidationResult.valid(), CALLER);

        assertThat(response.valid()).isFalse();
        assertThat(response.journeys().get(0).steps()).hasSize(1);
        assertThat(response.journeys().get(0).steps().get(0).error())
                .contains("No value matched");
        assertThat(response.findings()).extracting("code").contains("validator.step.failed");
    }

    @Test
    @DisplayName("a wrong status assertion returns response evidence")
    void statusAssertionFailureIsVisible() {
        ValidatorResponse response = service.validate(specification("$.token", 200),
                ValidationResult.valid(), CALLER);

        ValidatorResponse.StepResult order = response.journeys().get(0).steps().get(1);
        assertThat(response.valid()).isFalse();
        assertThat(order.response().status()).isEqualTo(201);
        assertThat(order.assertions().get(0).observed()).isEqualTo("201");
        assertThat(order.assertions().get(0).message()).contains("Expected status");
    }

    private PerformanceTestSpecification specification(String extraction, int expectedStatus) {
        StepSpec login = new StepSpec("login", "GET", "/login", Map.of(), Map.of(), null,
                List.of(new ExtractionSpec("session", ExtractionSource.JSON_BODY, extraction)),
                List.of(AssertionSpec.status(200)), null);
        StepSpec order = new StepSpec("create-order", "POST", "/orders", Map.of(),
                Map.of("session", "${session}"),
                new ScenarioSpec.RequestBody("application/json", "{\"sku\":\"demo\"}"),
                List.of(), List.of(AssertionSpec.status(expectedStatus)), null);
        ScenarioSpec scenario = new ScenarioSpec("checkout", 100, null, null, null,
                Map.of(), Map.of(), null, List.of(login, order));
        WorkloadSpec workload = new WorkloadSpec("api-load", WorkloadType.PROTOCOL, "jmeter",
                null, new LoadModel(LoadModelType.STAGES,
                List.of(new LoadStage(Duration.ofSeconds(1), 1))), List.of(scenario),
                null, null, null);
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("checkout", "team-a", "demo-service", Map.of()),
                new TestSpec(new EnvironmentSpec("performance", "local", TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), List.of(), List.of(workload), List.of(),
                        null, null));
    }

    private static TeamDirectory teamDirectory() {
        return new TeamDirectory() {
            @Override
            public Optional<TeamId> findByName(String name) {
                return "team-a".equals(name) ? Optional.of(TEAM) : Optional.empty();
            }

            @Override
            public Optional<ApplicationId> findApplication(TeamId teamId, String name) {
                return Optional.of(ApplicationId.generate());
            }
        };
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
