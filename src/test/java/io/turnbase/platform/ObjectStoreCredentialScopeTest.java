package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IMP-M10-21 / AUD-21: the applications authenticate to the object store with a bucket-limited
 * service credential, never the MinIO root account, and the compose and Helm provisioning create
 * that credential. These are text checks over the committed config — the same files the stack is
 * started from — so a change that points an app back at the root pair fails the build.
 */
class ObjectStoreCredentialScopeTest {

  private static final Path COMPOSE = Path.of("deploy/compose/docker-compose.yml");
  private static final Path POLICY = Path.of("deploy/compose/minio/app-policy.json");
  private static final Path HELM = Path.of("deploy/helm/turnbase/templates");

  @Test
  void composeAppsDoNotReferenceTheRootAccount() throws IOException {
    List<String> keyLines = lines(COMPOSE, "TURNBASE_ARTIFACT_STORE_ACCESS_KEY:", "_SECRET_KEY:");

    // platform, gamehost and the minio-init job, which creates the credential.
    assertThat(keyLines).hasSize(6).noneMatch(l -> l.contains("MINIO_ROOT"));
  }

  @Test
  void applicationConfigDoesNotReferenceTheRootAccount() throws IOException {
    Path yml = Path.of("src/main/resources/application.yml");

    assertThat(Files.readString(yml)).doesNotContain("MINIO_ROOT");
  }

  @Test
  void helmPlatformSecretDoesNotReferenceTheRootAccount() throws IOException {
    List<String> keyLines = lines(HELM.resolve("platform-secret.yaml"), "TURNBASE_ARTIFACT_STORE_");

    assertThat(keyLines)
        .hasSize(2)
        .noneMatch(l -> l.contains("rootUser") || l.contains("rootPassword"));
  }

  @Test
  void rootAccountIsOnlyHeldByTheServerAndTheInitStep() throws IOException {
    // In compose, the root variables appear in exactly the `minio` and `minio-init` services.
    String compose = Files.readString(COMPOSE);
    String afterInit = compose.substring(compose.indexOf("\n  platform:"));

    assertThat(afterInit).doesNotContain("MINIO_ROOT");
  }

  @Test
  void composeAndHelmInitStepsProvisionTheScopedCredential() throws IOException {
    for (Path init : List.of(COMPOSE, HELM.resolve("minio-init-job.yaml"))) {
      assertThat(Files.readString(init))
          .as(init.toString())
          .contains("mc admin policy create")
          .contains("mc admin user add")
          .contains("mc admin policy attach");
    }
  }

  @Test
  void composePolicyIsLimitedToTheBundleBucket() throws IOException {
    JsonNode statements = new ObjectMapper().readTree(Files.readString(POLICY)).get("Statement");

    assertThat(statements).isNotEmpty();
    for (JsonNode statement : statements) {
      assertThat(statement.get("Effect").asString()).isEqualTo("Allow");
      assertThat(statement.get("Action"))
          .allSatisfy(a -> assertThat(a.asString()).startsWith("s3:"));
      assertThat(statement.get("Action")).noneMatch(a -> a.asString().contains("*"));
      assertThat(statement.get("Resource"))
          .allSatisfy(
              r -> assertThat(r.asString()).matches("arn:aws:s3:::turnbase-bundles(/\\*)?"));
    }
  }

  @Test
  void helmPolicyIsTemplatedOnTheBucketOnly() throws IOException {
    String policy = Files.readString(HELM.resolve("minio-policy-configmap.yaml"));

    assertThat(policy)
        .contains("arn:aws:s3:::{{ .Values.minio.bucket }}\"")
        .contains("arn:aws:s3:::{{ .Values.minio.bucket }}/*\"")
        .doesNotContain("\"*\"")
        .doesNotContain("s3:*");
  }

  private static List<String> lines(Path file, String... needles) throws IOException {
    return Files.readAllLines(file).stream()
        .filter(l -> !l.stripLeading().startsWith("#"))
        .filter(l -> List.of(needles).stream().anyMatch(l::contains))
        .toList();
  }
}
