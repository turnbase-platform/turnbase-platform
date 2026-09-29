package io.turnbase.platform.game;

import io.grpc.servlet.jakarta.GrpcServlet;
import java.util.List;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code local-environment.md} §7.8: {@code GameHostRegistryService} shares the monolith's HTTP
 * listener rather than opening a second port. gRPC rides HTTP/2, so the servlet is mapped by
 * service path and needs {@code server.http2.enabled} (h2c) on the connector. It is registered
 * outside {@code DispatcherServlet}, so no MVC interceptor — including the {@code Host}
 * classification of {@code game-asset-serving.md} §4 — applies to it.
 *
 * <p>The registry is unauthenticated at the application layer; reachability is the control ({@code
 * orchestration-gamehost-protocol.md} *Security*: NetworkPolicy plus mTLS in production). The
 * ingress must never route {@code /turnbase.gamehost.*} from outside the cluster.
 */
@Configuration
class GrpcServletConfig {

  static final String REGISTRY_PATH = "/turnbase.gamehost.v1.GameHostRegistryService/*";

  @Bean
  ServletRegistrationBean<GrpcServlet> gameHostRegistryServlet(
      GameHostRegistryGrpcService registry) {
    ServletRegistrationBean<GrpcServlet> bean =
        new ServletRegistrationBean<>(new GrpcServlet(List.of(registry)), REGISTRY_PATH);
    bean.setAsyncSupported(true);
    bean.setName("gameHostRegistryGrpc");
    return bean;
  }
}
