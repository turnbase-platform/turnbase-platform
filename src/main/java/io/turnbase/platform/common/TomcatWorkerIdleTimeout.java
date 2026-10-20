package io.turnbase.platform.common;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * {@code IMP-M16-10}, {@code load-testing.md} §13 findings 5 and 17. Tomcat's worker pool releases
 * a thread above {@code min-spare} after it has waited 60 seconds for work, but the waiting threads
 * are served in turn: with a request every two seconds or so, no thread waits that long and the
 * pool stays where the last burst left it. The Game Host's heartbeats and registry calls arrive at
 * about that rate, so a platform that ran 12 workers before a load kept 29 of them for good (and
 * fell to 10 within 45 seconds once the Game Host was stopped). A shorter idle time makes the pool
 * follow the load, the trickle included.
 *
 * <p>Applied after the web server has started because Tomcat creates the executor then; Spring Boot
 * has no property for the idle time.
 */
@Component
class TomcatWorkerIdleTimeout implements ApplicationListener<WebServerInitializedEvent> {

  /** How long a worker above {@code min-spare} waits for work before it is released. */
  static final Duration IDLE = Duration.ofSeconds(10);

  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    if (event.getWebServer() instanceof TomcatWebServer tomcat) {
      for (Connector connector : tomcat.getTomcat().getService().findConnectors()) {
        apply(connector.getProtocolHandler().getExecutor());
      }
    }
  }

  static void apply(Executor executor) {
    if (executor instanceof ThreadPoolExecutor pool) {
      pool.setKeepAliveTime(IDLE.toMillis(), TimeUnit.MILLISECONDS);
    }
  }
}
