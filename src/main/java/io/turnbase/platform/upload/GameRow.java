package io.turnbase.platform.upload;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The columns of {@code game} this pipeline reads or writes — {@code data-model-catalog.md} §1. */
record GameRow(
    UUID gameId, @Nullable UUID ownerUserId, String badge, @Nullable UUID activeVersionId) {}
