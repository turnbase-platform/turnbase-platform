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
 * same port, routed by Host header — no second port, no second deployment. Proven at the socket
 * level because {@code java.net.http.HttpClient} (and {@code HttpURLConnection}) both refuse to let
 * a caller set the Host header themselves, since it names a "restricted" header.
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

      // Same listener, same routing: neither Host header changes what answers.
      assertThat(platformStatus).isEqualTo(gameStatus);
      assertThat(platformStatus).contains("HTTP/1.1");
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
