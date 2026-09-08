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
    registry.addInterceptor(new SessionAuthenticationInterceptor());
  }
}
