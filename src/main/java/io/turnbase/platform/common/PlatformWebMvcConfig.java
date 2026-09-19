package io.turnbase.platform.common;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.SortHandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the cross-cutting mechanisms this item and {@code IMP-M1-02} build into every controller in
 * the app: {@link GameOriginRoutingInterceptor} ({@code game-asset-serving.md} §4/§9 invariant 1,
 * ordered before everything else so a misrouted request never reaches authentication or the
 * handler), {@link OriginCheckInterceptor} (CSRF's second layer, ordered next so it runs "before
 * authentication" per {@code rest-conventions.md} regardless of {@code
 * session.SessionWebMvcConfig}'s own registration order), {@link RequestSizeInterceptor} (the
 * request-size gate), {@link RateLimitInterceptor} (Tiers 1-5) and the {@code Pageable} resolver
 * ({@code pagination-filtering-sorting.md} § Pagination — 1-indexed {@code page}, {@code size}
 * capped silently rather than rejected).
 */
@Configuration
public class PlatformWebMvcConfig implements WebMvcConfigurer {

  private final HttpProperties httpProperties;
  private final PaginationProperties paginationProperties;
  private final RateLimitInterceptor rateLimitInterceptor;
  private final OriginCheckInterceptor originCheckInterceptor;
  private final GameOriginRoutingInterceptor gameOriginRoutingInterceptor;

  public PlatformWebMvcConfig(
      HttpProperties httpProperties,
      PaginationProperties paginationProperties,
      RateLimitInterceptor rateLimitInterceptor,
      OriginCheckInterceptor originCheckInterceptor,
      GameOriginRoutingInterceptor gameOriginRoutingInterceptor) {
    this.httpProperties = httpProperties;
    this.paginationProperties = paginationProperties;
    this.rateLimitInterceptor = rateLimitInterceptor;
    this.originCheckInterceptor = originCheckInterceptor;
    this.gameOriginRoutingInterceptor = gameOriginRoutingInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // HIGHEST_PRECEDENCE is Integer.MIN_VALUE — subtracting from it would overflow to
    // Integer.MAX_VALUE (lowest precedence), so gameOriginRoutingInterceptor takes the highest
    // slot itself and originCheckInterceptor moves one step down instead.
    registry.addInterceptor(gameOriginRoutingInterceptor).order(Ordered.HIGHEST_PRECEDENCE);
    registry.addInterceptor(originCheckInterceptor).order(Ordered.HIGHEST_PRECEDENCE + 1);
    registry.addInterceptor(new RequestSizeInterceptor(httpProperties));
    registry.addInterceptor(rateLimitInterceptor);
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    PageableHandlerMethodArgumentResolver resolver =
        new PageableHandlerMethodArgumentResolver(new SortHandlerMethodArgumentResolver());
    resolver.setOneIndexedParameters(true);
    resolver.setMaxPageSize(paginationProperties.maxPageSize());
    resolver.setFallbackPageable(PageRequest.of(0, paginationProperties.defaultPageSize()));
    resolvers.add(resolver);
  }
}
