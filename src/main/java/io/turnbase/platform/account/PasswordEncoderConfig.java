package io.turnbase.platform.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * NFR-SEC-001, parameters from {@code input-validation.md} § Argon2id cost parameters — the
 * no-argument {@link Argon2PasswordEncoder} constructor is explicitly rejected there as
 * "meaningfully weaker than any current recommendation".
 */
@Configuration
class PasswordEncoderConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  }
}
