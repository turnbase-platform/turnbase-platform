package io.turnbase.platform.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only: exercises every {@link GlobalExceptionHandler} branch through real HTTP requests
 * rather than calling handler methods directly, so {@link GlobalExceptionHandlerIT} proves the
 * exact response bytes a client would see. Never shipped — {@code src/test/java} only.
 */
@RestController
@RequestMapping("/test/errors")
class ErrorHarnessController {

  private static final List<String> PAGE_SOURCE = List.of("alice", "bob", "carol", "dave", "erin");

  private final SortFieldValidator sortFieldValidator;

  ErrorHarnessController(SortFieldValidator sortFieldValidator) {
    this.sortFieldValidator = sortFieldValidator;
  }

  @PostMapping("/not-found")
  String notFound() {
    throw new NotFoundException("No such resource.");
  }

  @PostMapping("/conflict")
  String conflict() {
    throw new ConflictException("Username already taken.");
  }

  @PostMapping("/gone")
  String gone() {
    throw new GoneException("This upload slot has expired.");
  }

  @PostMapping("/unauthenticated")
  String unauthenticated() {
    throw new UnauthenticatedException("Missing or invalid session token.");
  }

  @PostMapping("/forbidden")
  String forbidden() {
    throw new ForbiddenException("You do not own this game.");
  }

  @PostMapping("/business-rule")
  String businessRule() {
    throw new BusinessRuleException(
        "The requested version cannot be uploaded.",
        List.of(
            new CheckError(
                "manifest.version",
                "Version 1.0.0 is not strictly higher than current version 1.2.0")));
  }

  @PostMapping("/rate-limit")
  String rateLimit() {
    throw new RateLimitExceededException("Too many requests.", 30);
  }

  @PostMapping("/service-unavailable")
  String serviceUnavailable() {
    throw new ServiceUnavailableException("Object storage is unreachable.");
  }

  @PostMapping("/room-unavailable-retry")
  String roomUnavailableRetry() {
    throw new RoomUnavailableRetryException(
        "The room's owning instance could not be reached.", 5_000);
  }

  @PostMapping("/internal")
  String internal() {
    throw new IllegalStateException("boom");
  }

  @PostMapping("/validate")
  String validate(@Valid @RequestBody TestPayload payload) {
    return payload.name();
  }

  @PostMapping("/constraint")
  String constraint(@RequestParam(name = "value") @Min(1) int value) {
    return "ok:" + value;
  }

  @PostMapping("/echo")
  int echo(@RequestBody byte[] body) {
    return body.length;
  }

  @PostMapping("/page")
  PageResponse<String> page(Pageable pageable) {
    sortFieldValidator.validate(pageable.getSort(), Set.of("name"));
    int fromIndex = Math.min((int) pageable.getOffset(), PAGE_SOURCE.size());
    int toIndex = Math.min(fromIndex + pageable.getPageSize(), PAGE_SOURCE.size());
    Page<String> page =
        new PageImpl<>(PAGE_SOURCE.subList(fromIndex, toIndex), pageable, PAGE_SOURCE.size());
    return PageResponse.from(page);
  }

  record TestPayload(@NotBlank String name) {}
}
