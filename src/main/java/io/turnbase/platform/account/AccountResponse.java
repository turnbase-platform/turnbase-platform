package io.turnbase.platform.account;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/users/me} — {@code rest-conventions.md} § Authentication, <em>The account
 * representation</em>. Deliberately has no field for the password hash or any {@code
 * provider_subject}.
 */
record AccountResponse(
    UUID userId,
    String username,
    @Nullable String email,
    @Nullable String emailSource,
    @Nullable Instant usernameChangeableAt,
    @Nullable Instant emailChangeableAt,
    List<String> authMethods,
    PrivacyResponse privacy) {}
