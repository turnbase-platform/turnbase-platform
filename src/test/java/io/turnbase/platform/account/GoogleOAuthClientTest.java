package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.turnbase.platform.common.PlatformOriginProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code oauth-rest-api.md} §3/§4. Stubs Google's token and userinfo endpoints with a plain JDK
 * {@link HttpServer} — no mock-HTTP library is a project dependency, and this is a two-call, fixed
 * JSON-shape client that does not need one.
 */
class GoogleOAuthClientTest {

  private @Nullable HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpServer startStubServer(String tokenResponse, String userInfoResponse)
      throws IOException {
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/token",
        exchange -> {
          respond(exchange, 200, tokenResponse);
        });
    stub.createContext(
        "/userinfo",
        exchange -> {
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          if (!"Bearer test-access-token".equals(authorization)) {
            respond(exchange, 401, "{}");
            return;
          }
          respond(exchange, 200, userInfoResponse);
        });
    stub.start();
    return stub;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private GoogleOAuthClient clientFor(HttpServer stub) {
    String base = "http://127.0.0.1:" + stub.getAddress().getPort();
    OAuthProperties properties =
        new OAuthProperties(
            "client-id",
            "client-secret",
            "https://accounts.google.com/o/oauth2/v2/auth",
            base + "/token",
            base + "/userinfo");
    return new GoogleOAuthClient(
        properties, new PlatformOriginProperties("http://turnbase.localhost:8080"));
  }

  @Test
  void authorizationUrlCarriesTheStateAndTheFixedCallbackRedirectUri() {
    GoogleOAuthClient client =
        new GoogleOAuthClient(
            new OAuthProperties(
                "client-id",
                "client-secret",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo"),
            new PlatformOriginProperties("http://turnbase.localhost:8080"));

    URI url = client.authorizationUrl("signed-state-value");

    assertThat(url.toString())
        .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
        .contains("client_id=client-id")
        .contains("redirect_uri=http%3A%2F%2Fturnbase.localhost%3A8080%2Fauth%2Fcallback")
        .contains("state=signed-state-value");
  }

  @Test
  void exchangeCodeResolvesTheIdentityFromAVerifiedEmail() throws IOException {
    server =
        startStubServer(
            "{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\"}",
            "{\"sub\":\"google-sub-1\",\"email\":\"alice@example.com\",\"email_verified\":true}");

    GoogleOAuthClient.GoogleIdentity identity = clientFor(server).exchangeCode("auth-code");

    assertThat(identity.sub()).isEqualTo("google-sub-1");
    assertThat(identity.email()).isEqualTo("alice@example.com");
  }

  @Test
  void exchangeCodeRejectsAnUnverifiedEmail() throws IOException {
    HttpServer stub =
        startStubServer(
            "{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\"}",
            "{\"sub\":\"google-sub-1\",\"email\":\"alice@example.com\",\"email_verified\":false}");
    server = stub;

    assertThatThrownBy(() -> clientFor(stub).exchangeCode("auth-code"))
        .isInstanceOf(GoogleOAuthClient.GoogleOAuthException.class);
  }

  @Test
  void exchangeCodePropagatesATokenEndpointFailure() throws IOException {
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/token", exchange -> respond(exchange, 400, "{\"error\":\"invalid_grant\"}"));
    stub.start();
    server = stub;

    assertThatThrownBy(() -> clientFor(stub).exchangeCode("bad-code"))
        .isInstanceOf(GoogleOAuthClient.GoogleOAuthException.class);
  }
}
