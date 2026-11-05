package io.turnbase.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an integration test that gets its context from {@link SharedApplication}: the schema is
 * left in place for it (the cached contexts keep running) and only the data is reset per test.
 * Without it {@link PostgresIntegrationTest} closes the cached contexts and drops the schema before
 * the class, as it does for a class that boots by hand.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UsesSharedApplication {}
