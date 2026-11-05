package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * local-environment.md §1 "Both origins are one listener": {@code turnbase.localhost} and {@code
 * {gameId}.games.localhost} are different registrable domains served by the same process on the
 * same port — no second port, no second deployment. Proven at the socket level because {@code
 * java.net.http.HttpClient} (and {@code HttpURLConnection}) both refuse to let a caller set the
 * Host header themselves, since it names a "restricted" header.
 *
 * <p>Both requests connecting to the identical port and each getting back a real HTTP response is
 * what proves "one listener" — it does not mean the two Host values are routed identically. {@code
 * game-asset-serving.md} §4/§9 invariant 1 ({@code IMP-M4-04}) requires the opposite: the {@code
 * Host} header alone must decide which set of routes a request may reach, so a path neither
 * origin's route table covers (a plain {@code GET /}) is expected to fail two different ways — a
 * game origin 404s on anything outside {@code /play/}/{@code /g/} before authentication is even
 * considered, while the platform origin falls through to its ordinary "session required" 401.
 *
 * <p>Keeps its own boot: it runs the single-listener topology, and a reused context carries other
 * tests' settings. (IMP-M20-10)
 *
 * <p>Requirements protected: {@code game-development-experience.md} FR-ASSET-001, FR-ASSET-002,
 * FR-ASSET-009.
 */
class SingleListenerOriginRoutingIT extends PostgresIntegrationTest {

  @Test
  void bothOriginsReachTheSameListener() throws Exception {
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
      int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));

      String platformStatus = statusLineFor(port, "turnbase.localhost:8080");
      String gameStatus = statusLineFor(port, "some-game.games.localhost:8080");

      // Same listener: both Host values reach a real HTTP server on the identical port (a
      // different listener would refuse the connection outright for at least one of them).
      assertThat(platformStatus).contains("HTTP/1.1");
      assertThat(gameStatus).contains("HTTP/1.1");
    } finally {
      context.close();
    }
  }

  private String statusLineFor(int port, String hostHeader) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET / HTTP/1.1\r\nHost: " + hostHeader + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      return reader.readLine();
    }
  }
}
