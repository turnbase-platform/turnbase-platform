package io.turnbase.platform.notification;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.PaginationMetadata;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** {@code notification-rest-api.md} §§2-6, the notification center's business logic. */
@Service
class NotificationService {

  private final NotificationRepository repository;

  NotificationService(NotificationRepository repository) {
    this.repository = repository;
  }

  /** §2. */
  PageResponse<NotificationResponse> list(UUID userId, Pageable pageable) {
    int pageSize = pageable.getPageSize();
    List<NotificationResponse> content =
        repository.list(userId, pageSize, (int) pageable.getOffset()).stream()
            .map(NotificationService::toResponse)
            .toList();
    long total = repository.count(userId);
    return new PageResponse<>(
        content,
        new PaginationMetadata(
            pageable.getPageNumber() + 1,
            pageSize,
            total,
            (int) Math.ceil((double) total / pageSize)));
  }

  /** §3. */
  long unreadCount(UUID userId) {
    return repository.unreadCount(userId);
  }

  /**
   * §4. {@code read: false} has no operation for this endpoint's absolute-value semantics — {@code
   * 422}, not a silent no-op.
   */
  NotificationResponse markRead(UUID notificationId, UUID userId, MarkReadRequest request) {
    if (Boolean.FALSE.equals(request.read())) {
      throw new BusinessRuleException("A notification cannot be marked unread.");
    }
    return repository
        .markRead(notificationId, userId)
        .map(NotificationService::toResponse)
        .orElseThrow(() -> new NotFoundException("No such notification."));
  }

  /** §5. */
  void markAllRead(UUID userId) {
    repository.markAllRead(userId);
  }

  /** §6. */
  void delete(UUID notificationId, UUID userId) {
    repository.delete(notificationId, userId);
  }

  private static NotificationResponse toResponse(NotificationRepository.NotificationRow row) {
    return new NotificationResponse(
        row.notificationId(), row.type(), row.payload(), row.createdAt(), row.readAt());
  }
}
