package io.turnbase.platform.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.turnbase.platform.common.CheckError;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Manifest validation ({@code manifest-schema.md} § Validation), realizing FR-MANIFEST-001…009 and
 * the IR-12 {@code capabilities.ai} rejection.
 *
 * <p>Layer 1 runs {@code manifest.schema.json} — the committed copy under {@code
 * src/main/resources/manifest} (conventions.md §5, IMP-GATE-60), kept in sync with the
 * authoritative spec file by {@code scripts/sync-manifest-schema.sh} — through a Draft 2020-12
 * engine rather than hand-written per-field checks. Layer 2 covers the cross-field rules the schema
 * cannot express. Unknown top-level and nested keys are reported as non-fatal warnings rather than
 * rejected: the schema is deliberately lenient on {@code additionalProperties}.
 *
 * <p>Archive-, JAR- and activation-level checks ({@code bundle-format.md} Phases A, B, C, E, F, G)
 * are the upload pipeline's own concern (IMP-M4-03), not this validator's — this class only ever
 * sees an already-parsed manifest document; confirming the bytes are valid JSON is Phase D1, the
 * caller's job.
 */
@Component
public final class ManifestValidator {

  private static final String SCHEMA_RESOURCE = "/manifest/manifest.schema.json";

  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          "manifestVersion",
          "title",
          "shortDescription",
          "version",
          "gdkApiVersion",
          "runtime",
          "backend",
          "frontend",
          "infoDoc",
          "tags",
          "capabilities",
          "teamTemplates",
          "parameters");
  private static final Set<String> BACKEND_KEYS = Set.of("path", "entryPoint");
  private static final Set<String> FRONTEND_KEYS = Set.of("entryPoint");
  private static final Set<String> CAPABILITIES_KEYS = Set.of("ai", "spectators", "midGameJoin");
  private static final Set<String> TEAM_TEMPLATE_KEYS = Set.of("name", "size", "count");
  private static final Set<String> RANGE_KEYS = Set.of("min", "max");
  private static final Set<String> PARAMETER_COMMON_KEYS =
      Set.of("key", "displayName", "type", "default");
  private static final Set<String> PARAMETER_ALL_TYPE_KEYS =
      Set.of("min", "max", "maxLength", "options");

  private final JsonSchema schema;

  public ManifestValidator() {
    this.schema = loadSchema();
  }

  private static JsonSchema loadSchema() {
    try (InputStream in = ManifestValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("manifest schema resource missing: " + SCHEMA_RESOURCE);
      }
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    } catch (IOException e) {
      throw new IllegalStateException("failed to load manifest schema", e);
    }
  }

  /** Runs both validation layers against an already-parsed manifest document. */
  public ManifestValidationResult validate(JsonNode manifest) {
    List<CheckError> errors = new ArrayList<>();
    List<CheckError> warnings = new ArrayList<>();

    for (ValidationMessage message : schema.validate(manifest)) {
      errors.add(new CheckError(toCheckId(message), message.getMessage()));
    }

    if (manifest.isObject()) {
      checkTeamTemplates(manifest, errors);
      checkParameters(manifest, errors);
      checkCapabilitiesAi(manifest, errors);
      warnUnknownKeys(manifest, warnings);
    }

    return new ManifestValidationResult(List.copyOf(errors), List.copyOf(warnings));
  }

  private static String toCheckId(ValidationMessage message) {
    String path = message.getInstanceLocation().toString();
    return path.equals("$") ? "manifest" : "manifest" + path.substring(1);
  }

  private void checkTeamTemplates(JsonNode manifest, List<CheckError> errors) {
    JsonNode teamTemplates = manifest.path("teamTemplates");
    if (!teamTemplates.isArray()) {
      return;
    }
    boolean multiple = teamTemplates.size() > 1;
    for (int i = 0; i < teamTemplates.size(); i++) {
      JsonNode template = teamTemplates.get(i);
      if (!template.isObject()) {
        continue;
      }
      String base = "manifest.teamTemplates[%d]".formatted(i);
      checkRange(template.path("size"), base + ".size", errors);
      checkRange(template.path("count"), base + ".count", errors);
      if (multiple && !template.hasNonNull("name")) {
        errors.add(
            new CheckError(
                base + ".name", "name is required when more than one team template is declared."));
      }
    }
  }

  private void checkRange(JsonNode range, String check, List<CheckError> errors) {
    if (!range.isObject()) {
      return;
    }
    JsonNode min = range.path("min");
    JsonNode max = range.path("max");
    if (min.isIntegralNumber() && max.isIntegralNumber() && max.asLong() < min.asLong()) {
      errors.add(
          new CheckError(
              check, "max (%d) is less than min (%d).".formatted(max.asLong(), min.asLong())));
    }
  }

  private void checkParameters(JsonNode manifest, List<CheckError> errors) {
    JsonNode parameters = manifest.path("parameters");
    if (!parameters.isArray()) {
      return;
    }
    Set<String> seenKeys = new HashSet<>();
    for (int i = 0; i < parameters.size(); i++) {
      JsonNode parameter = parameters.get(i);
      if (!parameter.isObject()) {
        continue;
      }
      checkParameterDefault(parameter, i, errors);

      String key = parameter.path("key").asText(null);
      if (key != null && !seenKeys.add(key)) {
        errors.add(
            new CheckError(
                "manifest.parameters", "key '%s' is declared more than once.".formatted(key)));
      }
    }
  }

  private void checkParameterDefault(JsonNode parameter, int index, List<CheckError> errors) {
    String check = "manifest.parameters[%d].default".formatted(index);
    JsonNode defaultNode = parameter.path("default");
    switch (parameter.path("type").asText("")) {
      case "integer", "decimal" -> {
        JsonNode min = parameter.path("min");
        JsonNode max = parameter.path("max");
        if (min.isNumber() && max.isNumber() && defaultNode.isNumber()) {
          double value = defaultNode.asDouble();
          if (value < min.asDouble() || value > max.asDouble()) {
            errors.add(
                new CheckError(
                    check,
                    "default %s is outside [%s, %s]."
                        .formatted(defaultNode.asText(), min.asText(), max.asText())));
          }
        }
      }
      case "string" -> {
        JsonNode maxLength = parameter.path("maxLength");
        if (maxLength.isIntegralNumber()
            && defaultNode.isTextual()
            && defaultNode.asText().length() > maxLength.asInt()) {
          errors.add(
              new CheckError(
                  check,
                  "default length %d exceeds maxLength %d."
                      .formatted(defaultNode.asText().length(), maxLength.asInt())));
        }
      }
      case "enum" -> {
        JsonNode options = parameter.path("options");
        if (options.isArray() && defaultNode.isTextual()) {
          Set<String> seenOptions = new HashSet<>();
          boolean matches = false;
          boolean duplicateOption = false;
          for (JsonNode option : options) {
            String value = option.asText();
            if (!seenOptions.add(value)) {
              duplicateOption = true;
            }
            if (value.equals(defaultNode.asText())) {
              matches = true;
            }
          }
          if (!matches) {
            errors.add(
                new CheckError(
                    check, "default '%s' is not one of options.".formatted(defaultNode.asText())));
          }
          if (duplicateOption) {
            errors.add(
                new CheckError(
                    "manifest.parameters[%d].options".formatted(index), "options must be unique."));
          }
        }
      }
      default -> {}
    }
  }

  private void checkCapabilitiesAi(JsonNode manifest, List<CheckError> errors) {
    JsonNode ai = manifest.path("capabilities").path("ai");
    if (ai.isBoolean() && ai.asBoolean()) {
      errors.add(
          new CheckError(
              "manifest.capabilities.ai",
              "AI players are not executable yet (game-contracts.md OQ-002); declare"
                  + " capabilities.ai as false."));
    }
  }

  private void warnUnknownKeys(JsonNode manifest, List<CheckError> warnings) {
    warnUnknownKeys(manifest, "manifest", TOP_LEVEL_KEYS, warnings);
    warnUnknownKeys(manifest.path("backend"), "manifest.backend", BACKEND_KEYS, warnings);
    warnUnknownKeys(manifest.path("frontend"), "manifest.frontend", FRONTEND_KEYS, warnings);
    warnUnknownKeys(
        manifest.path("capabilities"), "manifest.capabilities", CAPABILITIES_KEYS, warnings);

    JsonNode teamTemplates = manifest.path("teamTemplates");
    if (teamTemplates.isArray()) {
      for (int i = 0; i < teamTemplates.size(); i++) {
        String base = "manifest.teamTemplates[%d]".formatted(i);
        JsonNode template = teamTemplates.get(i);
        warnUnknownKeys(template, base, TEAM_TEMPLATE_KEYS, warnings);
        warnUnknownKeys(template.path("size"), base + ".size", RANGE_KEYS, warnings);
        warnUnknownKeys(template.path("count"), base + ".count", RANGE_KEYS, warnings);
      }
    }

    JsonNode parameters = manifest.path("parameters");
    if (parameters.isArray()) {
      for (int i = 0; i < parameters.size(); i++) {
        JsonNode parameter = parameters.get(i);
        Set<String> allowed =
            switch (parameter.path("type").asText("")) {
              case "integer", "decimal" -> union(PARAMETER_COMMON_KEYS, Set.of("min", "max"));
              case "string" -> union(PARAMETER_COMMON_KEYS, Set.of("maxLength"));
              case "enum" -> union(PARAMETER_COMMON_KEYS, Set.of("options"));
              case "boolean" -> PARAMETER_COMMON_KEYS;
              default -> union(PARAMETER_COMMON_KEYS, PARAMETER_ALL_TYPE_KEYS);
            };
        warnUnknownKeys(parameter, "manifest.parameters[%d]".formatted(i), allowed, warnings);
      }
    }
  }

  private void warnUnknownKeys(
      JsonNode node, String pathPrefix, Set<String> allowedKeys, List<CheckError> warnings) {
    if (!node.isObject()) {
      return;
    }
    node.fieldNames()
        .forEachRemaining(
            fieldName -> {
              if (!allowedKeys.contains(fieldName)) {
                warnings.add(
                    new CheckError(
                        pathPrefix + "." + fieldName,
                        "Unrecognized key '%s'.".formatted(fieldName)));
              }
            });
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> result = new HashSet<>(a);
    result.addAll(b);
    return Set.copyOf(result);
  }
}
