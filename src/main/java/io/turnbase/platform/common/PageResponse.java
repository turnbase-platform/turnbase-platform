package io.turnbase.platform.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The reusable list-endpoint envelope from {@code pagination-filtering-sorting.md} § Response
 * Envelope, built once so every catalog/room/notification/history listing shares it rather than
 * hand-rolling the same two fields.
 */
public record PageResponse<T>(List<T> content, PaginationMetadata pagination) {

  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(
        page.getContent(),
        new PaginationMetadata(
            page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages()));
  }

  public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
    return new PageResponse<>(
        page.getContent().stream().map(mapper).toList(),
        new PaginationMetadata(
            page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages()));
  }
}
