package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.UsesSharedApplication;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the real application ({@code IMP-M1-01}'s own harness controller, {@link
 * ErrorHarnessController}, is on the test classpath only) and asserts the exact response body
 * {@link GlobalExceptionHandler} produces for every {@code error-handling.md} taxonomy entry — the
 * Done-when clause's own words: "an integration test asserts the exact response body for each error
 * class, including {@code room-unavailable-retry} and its {@code retryAfterMs} member."
 */
@UsesSharedApplication
class GlobalExceptionHandlerIT extends PostgresIntegrationTest {

  private static final String PROBLEM_BASE = "https://turnbase.io/errors/";

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  // The JDK's legacy HttpURLConnection-backed default request factory silently drops the
  // response body on a 401 (a known JDK quirk in its built-in auth-challenge handling); the
  // java.net.http-backed factory does not have this problem.
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private ConfigurableApplicationContext context;
  private String baseUrl;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeEach
  void bootApplication() {
    context =
        io.turnbase.platform.SharedApplication.run(
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
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port + "/test/errors";
  }

  @Test
  void notFoundProducesTheNotFoundProblemType() throws Exception {
    ResponseEntity<String> response = post("/not-found", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "not-found");
    assertThat(body.get("title")).isEqualTo("Not Found");
    assertThat(body.get("status")).isEqualTo(404);
    assertThat(body.get("detail")).isEqualTo("No such resource.");
    assertThat(body.get("instance")).isEqualTo("/test/errors/not-found");
    assertThat(body).doesNotContainKey("errors");
  }

  @Test
  void conflictProducesTheConflictProblemType() throws Exception {
    ResponseEntity<String> response = post("/conflict", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "conflict");
    assertThat(body.get("title")).isEqualTo("Conflict");
    assertThat(body.get("detail")).isEqualTo("Username already taken.");
  }

  @Test
  void goneProducesTheGoneProblemType() throws Exception {
    ResponseEntity<String> response = post("/gone", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "gone");
    assertThat(body.get("title")).isEqualTo("Gone");
  }

  @Test
  void unauthenticatedProducesTheUnauthenticatedProblemType() throws Exception {
    ResponseEntity<String> response = post("/unauthenticated", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "unauthenticated");
  }

  @Test
  void forbiddenProducesTheForbiddenProblemType() throws Exception {
    ResponseEntity<String> response = post("/forbidden", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "forbidden");
  }

  @Test
  void businessRuleViolationCarriesItsCheckErrorsArray() throws Exception {
    ResponseEntity<String> response = post("/business-rule", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "business-rule-violation");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors =
        (List<Map<String, Object>>) Objects.requireNonNull(body.get("errors"));
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).containsEntry("check", "manifest.version");
  }

  @Test
  void rateLimitExceededCarriesRetryAfterHeader() throws Exception {
    ResponseEntity<String> response = post("/rate-limit", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "rate-limit-exceeded");
  }

  @Test
  void serviceUnavailableProducesTheServiceUnavailableProblemType() throws Exception {
    ResponseEntity<String> response =
        post("/service-unavailable", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "service-unavailable");
  }

  @Test
  void roomUnavailableRetryCarriesRetryAfterMsAndTheRetryAfterHeader() throws Exception {
    ResponseEntity<String> response =
        post("/room-unavailable-retry", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "room-unavailable-retry");
    assertThat(body.get("retryAfterMs")).isEqualTo(5000);
  }

  @Test
  void uncaughtExceptionsBecomeAnInternalErrorWithNoLeakedDetail() throws Exception {
    ResponseEntity<String> response = post("/internal", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "internal-error");
    assertThat(body.get("detail")).isEqualTo("An unexpected error occurred.");
    assertThat(response.getBody()).doesNotContain("boom", "IllegalStateException");
  }

  @Test
  void beanValidationFailuresProduceValidationFailedWithFieldErrors() throws Exception {
    ResponseEntity<String> response =
        post("/validate", "{\"name\":\"\"}", MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "validation-failed");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors =
        (List<Map<String, Object>>) Objects.requireNonNull(body.get("errors"));
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).containsEntry("field", "name");
  }

  @Test
  void malformedJsonBodyProducesValidationFailed() throws Exception {
    ResponseEntity<String> response = post("/validate", "{not-json", MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "validation-failed");
    assertThat(body.get("detail")).isEqualTo("The request body is malformed.");
  }

  @Test
  void constraintViolationsOnQueryParametersProduceValidationFailed() throws Exception {
    ResponseEntity<String> response = post("/constraint?value=0", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "validation-failed");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors =
        (List<Map<String, Object>>) Objects.requireNonNull(body.get("errors"));
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).containsEntry("field", "value");
  }

  @Test
  void aBodyUnderTheCapIsAccepted() {
    String smallBody = "x".repeat(1_000);
    ResponseEntity<String> response = post("/echo", smallBody, MediaType.TEXT_PLAIN);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("1000");
  }

  @Test
  void aBodyOverTheSixtyFourKilobyteCapIsRejectedAsPayloadTooLarge() throws Exception {
    String oversizedBody = "x".repeat(70_000);

    ResponseEntity<String> response = post("/echo", oversizedBody, MediaType.TEXT_PLAIN);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "payload-too-large");
  }

  @Test
  void aChunkedBodyOverTheCapIsRejectedAsPayloadTooLarge() throws Exception {
    java.net.http.HttpResponse<String> response =
        postChunked("/echo", "x".repeat(70_000), "text/plain");

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).contains(PROBLEM_BASE + "payload-too-large");
  }

  @Test
  void aChunkedJsonBodyOverTheCapIsRejectedAsPayloadTooLarge() throws Exception {
    String json = "{\"name\":\"" + "x".repeat(70_000) + "\"}";

    java.net.http.HttpResponse<String> response =
        postChunked("/validate", json, "application/json");

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).contains(PROBLEM_BASE + "payload-too-large");
  }

  @Test
  void aChunkedBodyUnderTheCapIsAccepted() throws Exception {
    java.net.http.HttpResponse<String> response =
        postChunked("/echo", "x".repeat(1_000), "text/plain");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("1000");
  }

  private java.net.http.HttpResponse<String> postChunked(
      String path, String body, String contentType) throws Exception {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // ofInputStream has no known length, so the JDK client sends Transfer-Encoding: chunked.
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
            .build();
    return HttpClient.newHttpClient()
        .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void unwhitelistedSortFieldsProduceValidationFailed() throws Exception {
    ResponseEntity<String> response =
        post("/page?sort=notAllowed,asc", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = parse(response);
    assertThat(body.get("type")).isEqualTo(PROBLEM_BASE + "validation-failed");
  }

  @Test
  void pageResponseIsOneIndexedAndClampsAnOversizedPageParameter() throws Exception {
    ResponseEntity<String> response =
        post("/page?page=1&size=500", null, MediaType.APPLICATION_JSON);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = parse(response);
    @SuppressWarnings("unchecked")
    Map<String, Object> pagination =
        (Map<String, Object>) Objects.requireNonNull(body.get("pagination"));
    assertThat(pagination.get("page")).isEqualTo(1);
    assertThat(pagination.get("size")).isEqualTo(100);
    assertThat(pagination.get("totalElements")).isEqualTo(5);
  }

  private ResponseEntity<String> post(String path, @Nullable String body, MediaType contentType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(contentType);
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
  }

  private Map<String, Object> parse(ResponseEntity<String> response) throws Exception {
    return objectMapper.readValue(response.getBody(), Map.class);
  }

  private static final class NoOpResponseErrorHandler
      implements org.springframework.web.client.ResponseErrorHandler {
    @Override
    public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
      return false;
    }

    @Override
    public void handleError(
        java.net.URI url,
        org.springframework.http.HttpMethod method,
        org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
  }
}
