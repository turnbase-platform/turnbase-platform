package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.account.AccountProperties;
import io.turnbase.platform.account.OAuthProperties;
import io.turnbase.platform.admin.AdminProperties;
import io.turnbase.platform.common.GameFrontendProperties;
import io.turnbase.platform.common.HttpProperties;
import io.turnbase.platform.common.JobsProperties;
import io.turnbase.platform.common.PaginationProperties;
import io.turnbase.platform.common.PlatformOriginProperties;
import io.turnbase.platform.common.RateLimitProperties;
import io.turnbase.platform.common.RetentionProperties;
import io.turnbase.platform.common.SecurityProperties;
import io.turnbase.platform.room.RoomProperties;
import io.turnbase.platform.session.AuthProperties;
import io.turnbase.platform.social.SocialProperties;
import io.turnbase.platform.upload.UploadProperties;
import io.turnbase.platform.websocket.WebSocketRateLimitProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * local-environment.md §7 claims to be "the whole tunable surface" (IMP-M0-09's Done-when): every
 * value asserted here is bound through the real {@code application.yml} on the test classpath, not
 * re-declared, so a default drifting from what §7 states fails this test rather than being noticed
 * three milestones later.
 */
class ConfigurationDefaultsTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(PropertiesConfig.class)
          .withPropertyValues("turnbase.admin.username=admin", "turnbase.admin.password=admin");

  @Test
  void bindsEveryTunableToItsSection7Default() {
    contextRunner.run(
        context -> {
          RoomProperties room = context.getBean(RoomProperties.class);
          assertThat(room.gracePeriod()).isEqualTo(Duration.ofSeconds(60));
          assertThat(room.migrationWindow()).isEqualTo(Duration.ofSeconds(20));
          assertThat(room.idleWindow()).isEqualTo(Duration.ofMinutes(30));
          assertThat(room.spectatorCapacity()).isEqualTo(8);
          assertThat(room.lease().ttl()).isEqualTo(Duration.ofSeconds(10));
          assertThat(room.lease().renewInterval()).isEqualTo(Duration.ofSeconds(3));
          assertThat(room.lease().selfEvictAfter()).isEqualTo(Duration.ofSeconds(6));

          JobsProperties jobs = context.getBean(JobsProperties.class);
          assertThat(jobs.deadlineSweep().interval()).isEqualTo(Duration.ofSeconds(5));
          assertThat(jobs.reconcile().interval()).isEqualTo(Duration.ofSeconds(60));
          assertThat(jobs.reconcile().startupSuppression()).isEqualTo(Duration.ofSeconds(120));
          assertThat(jobs.reconcile().orphanAge()).isEqualTo(Duration.ofMinutes(10));
          assertThat(jobs.guestCleanup().interval()).isEqualTo(Duration.ofHours(1));
          assertThat(jobs.notificationPurge().interval()).isEqualTo(Duration.ofHours(24));
          assertThat(jobs.expiryPurge().interval()).isEqualTo(Duration.ofHours(1));
          assertThat(jobs.roomPurge().interval()).isEqualTo(Duration.ofHours(24));
          assertThat(jobs.uploadSlotCleanup().interval()).isEqualTo(Duration.ofMinutes(15));

          RetentionProperties retention = context.getBean(RetentionProperties.class);
          assertThat(retention.closedRoom()).isEqualTo(Duration.ofDays(7));
          assertThat(retention.notification()).isEqualTo(Duration.ofDays(30));
          assertThat(retention.friendRequest()).isEqualTo(Duration.ofDays(30));
          assertThat(retention.guestAccount()).isEqualTo(Duration.ofHours(24));

          AuthProperties auth = context.getBean(AuthProperties.class);
          assertThat(auth.sessionTtl()).isEqualTo(Duration.ofHours(24));
          assertThat(auth.jwtKeySetRequired()).isFalse();
          assertThat(auth.jwtKeySet()).isEmpty();

          AccountProperties account = context.getBean(AccountProperties.class);
          assertThat(account.renameWindow()).isEqualTo(Duration.ofDays(30));

          SocialProperties social = context.getBean(SocialProperties.class);
          assertThat(social.friendCap()).isEqualTo(50);

          PaginationProperties pagination = context.getBean(PaginationProperties.class);
          assertThat(pagination.defaultPageSize()).isEqualTo(25);
          assertThat(pagination.maxPageSize()).isEqualTo(100);

          UploadProperties uploads = context.getBean(UploadProperties.class);
          assertThat(uploads.maxBundleSize()).isEqualTo(DataSize.ofMegabytes(256));
          assertThat(uploads.slotTtl()).isEqualTo(Duration.ofHours(1));
          assertThat(uploads.validationLease()).isEqualTo(Duration.ofMinutes(10));

          HttpProperties http = context.getBean(HttpProperties.class);
          assertThat(http.maxBodySize()).isEqualTo(DataSize.ofKilobytes(64));

          RateLimitProperties rateLimit = context.getBean(RateLimitProperties.class);
          assertThat(rateLimit.enabled()).isTrue();
          RateLimitProperties.Switches switches = rateLimit.switches();
          assertThat(switches.authLockout()).isTrue();
          assertThat(switches.api()).isTrue();
          assertThat(switches.upload()).isTrue();
          assertThat(switches.publicTier()).isTrue();
          assertThat(switches.guestMinting()).isTrue();
          assertThat(switches.registrationMinting()).isTrue();

          WebSocketRateLimitProperties.Switches wsSwitches =
              context.getBean(WebSocketRateLimitProperties.class).switches();
          assertThat(wsSwitches.connection()).isTrue();
          assertThat(wsSwitches.snapshot()).isTrue();
          assertThat(wsSwitches.action()).isTrue();
          assertThat(wsSwitches.chat()).isTrue();
          assertThat(wsSwitches.command()).isTrue();
          assertThat(wsSwitches.subscribe()).isTrue();
          assertThat(wsSwitches.violationLockout()).isTrue();
          assertThat(wsSwitches.maxConnectionsPerUser()).isTrue();

          SecurityProperties security = context.getBean(SecurityProperties.class);
          assertThat(security.trustedProxies()).containsExactly("10.0.0.0/8", "172.16.0.0/12");

          PlatformOriginProperties platformOrigin = context.getBean(PlatformOriginProperties.class);
          assertThat(platformOrigin.origin()).isEqualTo("http://turnbase.localhost:8080");

          GameFrontendProperties gameFrontend = context.getBean(GameFrontendProperties.class);
          assertThat(gameFrontend.originTemplate())
              .isEqualTo("http://{gameId}.games.localhost:8080");

          OAuthProperties oauth = context.getBean(OAuthProperties.class);
          assertThat(oauth.configured()).isFalse();

          AdminProperties admin = context.getBean(AdminProperties.class);
          assertThat(admin.username()).isEqualTo("admin");
          assertThat(admin.password()).isEqualTo("admin");
        });
  }

  @Test
  void loadtestProfileTurnsEverySwitchOffExceptTheConnectionCap() {
    contextRunner
        .withPropertyValues("spring.profiles.active=local,loadtest")
        .run(
            context -> {
              RateLimitProperties rateLimit = context.getBean(RateLimitProperties.class);
              assertThat(rateLimit.enabled()).isTrue();
              RateLimitProperties.Switches switches = rateLimit.switches();
              assertThat(switches.authLockout()).isFalse();
              assertThat(switches.api()).isFalse();
              assertThat(switches.upload()).isFalse();
              assertThat(switches.publicTier()).isFalse();
              assertThat(switches.guestMinting()).isFalse();
              assertThat(switches.registrationMinting()).isFalse();

              WebSocketRateLimitProperties.Switches ws =
                  context.getBean(WebSocketRateLimitProperties.class).switches();
              assertThat(ws.connection()).isFalse();
              assertThat(ws.snapshot()).isFalse();
              assertThat(ws.action()).isFalse();
              assertThat(ws.chat()).isFalse();
              assertThat(ws.command()).isFalse();
              assertThat(ws.subscribe()).isFalse();
              assertThat(ws.violationLockout()).isFalse();
              assertThat(ws.maxConnectionsPerUser()).isTrue();
            });
  }

  @Configuration
  @EnableConfigurationProperties({
    RoomProperties.class,
    JobsProperties.class,
    RetentionProperties.class,
    AuthProperties.class,
    AccountProperties.class,
    SocialProperties.class,
    PaginationProperties.class,
    UploadProperties.class,
    HttpProperties.class,
    RateLimitProperties.class,
    WebSocketRateLimitProperties.class,
    SecurityProperties.class,
    PlatformOriginProperties.class,
    GameFrontendProperties.class,
    OAuthProperties.class,
    AdminProperties.class
  })
  static class PropertiesConfig {}
}
