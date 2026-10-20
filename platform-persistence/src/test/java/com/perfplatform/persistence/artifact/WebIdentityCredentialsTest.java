package com.perfplatform.persistence.artifact;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsWebIdentityTokenFileCredentialsProvider;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves projected identity support is packaged and exchanges tokens without a real cloud account. */
class WebIdentityCredentialsTest {
    @TempDir Path directory;

    @Test
    void exchangesAProjectedTokenForTemporaryCredentials() throws Exception {
        Path token = directory.resolve("token");
        Files.writeString(token, "fixture-web-identity-token");
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    <AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
                      <AssumeRoleWithWebIdentityResult><Credentials>
                        <AccessKeyId>fixture-access</AccessKeyId><SecretAccessKey>fixture-secret</SecretAccessKey>
                        <SessionToken>fixture-session</SessionToken><Expiration>2037-01-01T00:00:00Z</Expiration>
                      </Credentials></AssumeRoleWithWebIdentityResult>
                      <ResponseMetadata><RequestId>fixture-request</RequestId></ResponseMetadata>
                    </AssumeRoleWithWebIdentityResponse>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (StsClient sts = StsClient.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .credentialsProvider(AnonymousCredentialsProvider.create()).build();
             var provider = StsWebIdentityTokenFileCredentialsProvider.builder().stsClient(sts)
                     .webIdentityTokenFile(token).roleArn("arn:aws:iam::111122223333:role/fixture")
                     .roleSessionName("performance-fixture").build()) {
            assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("fixture-access");
            assertThat(request.get()).contains("Action=AssumeRoleWithWebIdentity", "fixture-web-identity-token");
        } finally {
            server.stop(0);
        }
    }
}
