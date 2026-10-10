package io.turnbase.platform.admin;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the admin REST surface as beans of the management (actuator) child context rather than
 * the main one — {@code rest-conventions.md} § *Actuator and the Management Port*: the admin API
 * and actuator are deliberately the same listener, and a plain {@code @RestController} in the main
 * application context is never served there. {@code ManagementContextType.CHILD} is correct rather
 * than {@code SAME} because {@code management.server.port} differs from {@code server.port} ({@code
 * local-environment.md} §1), which is exactly the case Spring Boot gives its own separate child
 * {@code ApplicationContext} — one with the main context as its parent, so every admin bean below
 * can still autowire {@code JdbcClient}, {@code AdminProperties} and {@code
 * upload.VersionUploadService} from it.
 */
@ManagementContextConfiguration(ManagementContextType.CHILD)
@Import({
  AdminGameRepository.class,
  AdminAuditLogger.class,
  AdminGameService.class,
  AdminReviewService.class,
  AdminGameController.class,
  AdminReviewController.class,
  AdminUploadController.class,
})
class AdminManagementContextConfiguration {

  @Bean
  AdminBasicAuthInterceptor adminBasicAuthInterceptor(
      AdminProperties adminProperties, JsonMapper objectMapper) {
    return new AdminBasicAuthInterceptor(adminProperties, objectMapper);
  }

  /**
   * {@code admin-tooling.md} FR-ADM-002 — every {@code /admin/**} route, never {@code
   * /actuator/**}.
   */
  @Bean
  WebMvcConfigurer adminBasicAuthWebMvcConfigurer(AdminBasicAuthInterceptor interceptor) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/admin/**");
      }
    };
  }
}
