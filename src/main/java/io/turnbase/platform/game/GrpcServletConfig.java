package io.turnbase.platform.game;

import io.grpc.servlet.jakarta.GrpcServlet;
import java.util.List;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
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

  /**
   * With h2c enabled, Tomcat buffers the body of any request that asks for an {@code Upgrade: h2c}
   * (the JDK HTTP client does, for every POST) and answers its own HTML 413 once the body passes
   * {@code maxSavePostSize} — 4 KiB by default — before the request reaches {@code
   * RequestSizeInterceptor}, so the problem-details 413 of {@code error-handling.md} is lost. 1 MiB
   * is far above the 64 KiB body cap and still a bound on what an upgrading client can make the
   * container hold.
   */
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> upgradeBodyBuffer() {
    return factory ->
        factory.addConnectorCustomizers(connector -> connector.setMaxSavePostSize(1024 * 1024));
  }
}
