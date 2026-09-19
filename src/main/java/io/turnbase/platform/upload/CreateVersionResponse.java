package io.turnbase.platform.upload;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** {@code file-uploads.md} step 1's {@code 201} body. */
record CreateVersionResponse(UUID uploadId, URI uploadUrl, Instant expiresAt) {}
