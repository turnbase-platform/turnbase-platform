package io.turnbase.platform.common;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.SortHandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the two cross-cutting mechanisms this item builds into every controller in the app: {@link
 * RequestSizeInterceptor} (the request-size gate) and the {@code Pageable} resolver ({@code
 * pagination-filtering-sorting.md} § Pagination — 1-indexed {@code page}, {@code size} capped
 * silently rather than rejected).
 */
@Configuration
public class PlatformWebMvcConfig implements WebMvcConfigurer {

  private final HttpProperties httpProperties;
  private final PaginationProperties paginationProperties;

  public PlatformWebMvcConfig(
      HttpProperties httpProperties, PaginationProperties paginationProperties) {
    this.httpProperties = httpProperties;
    this.paginationProperties = paginationProperties;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new RequestSizeInterceptor(httpProperties));
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
