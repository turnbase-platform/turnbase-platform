package io.turnbase.platform.session;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link SessionAuthenticationInterceptor} into every controller. A separate {@code
 * WebMvcConfigurer} bean from {@code common.PlatformWebMvcConfig} rather than added to it — {@code
 * common} depends on no feature package, and the interceptor is this feature's own enforcement of
 * NFR-SEC-006, not a generic HTTP mechanism.
 */
@Configuration
class SessionWebMvcConfig implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // The admin management child context inherits this bean via the hierarchical bean factory
    // (WebMvcConfigurer collection autowiring searches parent contexts too), so without this
    // exclusion /admin/** would additionally be rejected for lacking a session cookie — on top of,
    // and ahead of, admin.AdminBasicAuthInterceptor's own check. /admin/** authenticates by HTTP
    // Basic only (admin-tooling.md FR-ADM-002); it is never session-authenticated.
    registry
        .addInterceptor(new SessionAuthenticationInterceptor())
        .excludePathPatterns("/admin/**");
  }
}
