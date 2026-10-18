package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * IMP-M15-04: after one request on the main port, the management port's Prometheus endpoint carries
 * {@code http_server_requests_seconds_bucket} lines, i.e. the percentile histogram is on by default
 * (observability.md §2 <em>Exposure</em>; FR-MET-001).
 */
class HttpLatencyHistogramIT extends PostgresIntegrationTest {

  private static final Duration POLL_LIMIT = Duration.ofSeconds(5);

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void prometheusEndpointExposesHttpServerRequestBuckets() throws Exception {
    ConfigurableApplicationContext context =
        SpringApplication.run(
            TurnBaseApplication.class,
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
            "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
            "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
            "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
            "--turnbase.admin.username=admin",
            "--turnbase.admin.password=admin",
            "--spring.data.redis.url=" + redisUrl());
    try {
      int mainPort = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
      int managementPort =
          Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));

      get(mainPort, "/api/auth/providers");

      long deadline = System.nanoTime() + POLL_LIMIT.toNanos();
      Optional<String> bucket = Optional.empty();
      while (bucket.isEmpty() && System.nanoTime() < deadline) {
        bucket = bucketLine(get(managementPort, "/actuator/prometheus"));
        if (bucket.isEmpty()) {
          Thread.sleep(100);
        }
      }

      assertThat(bucket)
          .as("an http_server_requests_seconds_bucket line with an le label")
          .isPresent();
    } finally {
      context.close();
    }
  }

  private static Optional<String> bucketLine(String body) {
    return body.lines()
        .filter(line -> line.startsWith("http_server_requests_seconds_bucket{"))
        .filter(line -> line.contains("le=\""))
        .findFirst();
  }

  private String get(int port, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
  }
}
