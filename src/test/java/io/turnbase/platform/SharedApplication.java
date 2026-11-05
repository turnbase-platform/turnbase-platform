package io.turnbase.platform;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A cache of booted application contexts for the integration tests (IMP-M20-10). A boot costs
 * seconds and the ITs used to pay it in every {@code @BeforeEach}; {@link #run} instead returns the
 * context already booted for the same property overrides, and gives the caller the state a fresh
 * boot would have had: every table but Flyway's history and ShedLock's locks emptied (the lock
 * provider remembers which lock rows exist and silently skips a job whose row was deleted under it;
 * a released lock is inert), Redis emptied of everything an instance does not write about itself at
 * boot, the bundle bucket emptied. The migrations seed no rows (no {@code INSERT} in {@code
 * db/migration}) and the JWT key set lives in memory, so a truncated database is a first-boot
 * database.
 *
 * <p>At most one context is live at once. Contexts are keyed by their full argument list: classes
 * that pass the same overrides share a context, and a class that needs different overrides gets its
 * own. They live until the JVM ends, or until a class that boots by hand drops the schema ({@link
 * #closeAll()}). A test that must own its boot (two instances, thread bounds, boot behaviour) keeps
 * calling {@link SpringApplication} directly and says why in its Javadoc.
 *
 * <p>Callers must not close the returned context.
 */
public final class SharedApplication {

  private static final Map<List<String>, ConfigurableApplicationContext> CACHE =
      new LinkedHashMap<>();

  private SharedApplication() {}

  /** The context for these overrides (booted on first use), over an emptied database. */
  public static ConfigurableApplicationContext run(String... args) {
    return run(new Class<?>[] {TurnBaseApplication.class}, args);
  }

  /** As {@link #run(String...)}, with extra configuration classes beside the application. */
  public static synchronized ConfigurableApplicationContext run(Class<?>[] sources, String[] args) {
    List<String> key = new ArrayList<>(List.of(args));
    for (Class<?> source : sources) {
      key.add(source.getName());
    }
    ConfigurableApplicationContext context = CACHE.get(key);
    if (context == null || !context.isActive()) {
      // One live context at a time: an idle second one would keep running its scheduled jobs
      // against the rows of the one under test.
      closeAll();
      context = SpringApplication.run(sources, args);
      CACHE.put(key, context);
    }
    resetData();
    return context;
  }

  /** Whether any context is live, i.e. whether the schema under it is in use. */
  static synchronized boolean hasLiveContexts() {
    return CACHE.values().stream().anyMatch(ConfigurableApplicationContext::isActive);
  }

  /** Closes every cached context; called before the schema is dropped from under them. */
  static synchronized void closeAll() {
    CACHE.values().forEach(ConfigurableApplicationContext::close);
    CACHE.clear();
  }

  /** Empties the data stores without touching the schema. */
  static void resetData() {
    List<String> tables = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(
                PostgresIntegrationTest.POSTGRES.getJdbcUrl(),
                PostgresIntegrationTest.POSTGRES.getUsername(),
                PostgresIntegrationTest.POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      try (var rows =
          statement.executeQuery(
              "select tablename from pg_tables where schemaname = 'public'"
                  + " and tablename not in ('flyway_schema_history', 'shedlock')")) {
        while (rows.next()) {
          tables.add("\"" + rows.getString(1) + "\"");
        }
      }
      if (!tables.isEmpty()) {
        statement.execute("truncate " + String.join(", ", tables) + " restart identity cascade");
      }
      // Everything but what a live instance wrote about itself at boot: its liveness key and the
      // platform sentinel are not recreated until the next boot (or lease renewal, which a test
      // configures to an hour).
      PostgresIntegrationTest.REDIS.execInContainer(
          "sh",
          "-c",
          "redis-cli --scan | grep -Ev '^(pod:.*:alive|platform:sentinel)$'"
              + " | xargs -r redis-cli del");
    } catch (SQLException | java.io.IOException e) {
      throw new IllegalStateException("could not reset the shared data stores", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    emptyBucket();
  }

  private static void emptyBucket() {
    var minio = PostgresIntegrationTest.MINIO;
    try (S3Client client =
        S3Client.builder()
            .endpointOverride(URI.create(minio.getS3URL()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
            .forcePathStyle(true)
            .build()) {
      String bucket = PostgresIntegrationTest.ARTIFACT_BUCKET;
      for (S3Object object :
          client
              .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
              .contents()) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
      }
    }
  }
}
