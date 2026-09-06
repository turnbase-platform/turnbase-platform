package io.turnbase.platform.common;

/**
 * {@code pagination-filtering-sorting.md} § Response Envelope. {@code page} is 1-indexed to match
 * the {@code page} query parameter — {@link org.springframework.data.domain.Page#getNumber()} is
 * always 0-indexed regardless of the resolver's indexing mode, so {@link PageResponse#from} adds
 * the 1 back before this reaches a client.
 */
public record PaginationMetadata(int page, int size, long totalElements, int totalPages) {}
