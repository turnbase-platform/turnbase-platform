package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * IMP-M15-04: {@code http.server.requests} publishes a percentile histogram between 5 ms and 30 s,
 * so FR-MET-001's latency quantiles and the {@code HighLatencyP95} alert can be evaluated.
 */
class HttpLatencyHistogramTest {

  private static final String DISTRIBUTION = "management.metrics.distribution.";

  @Test
  void httpServerRequestsPublishesAPercentileHistogramBetween5msAnd30s() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              Binder binder = Binder.get(context.getEnvironment());
              assertThat(
                      binder
                          .bind(
                              DISTRIBUTION + "percentiles-histogram.http.server.requests",
                              Boolean.class)
                          .get())
                  .isTrue();
              assertThat(
                      binder
                          .bind(
                              DISTRIBUTION + "minimum-expected-value.http.server.requests",
                              Duration.class)
                          .get())
                  .isEqualTo(Duration.ofMillis(5));
              assertThat(
                      binder
                          .bind(
                              DISTRIBUTION + "maximum-expected-value.http.server.requests",
                              Duration.class)
                          .get())
                  .isEqualTo(Duration.ofSeconds(30));
            });
  }
}
