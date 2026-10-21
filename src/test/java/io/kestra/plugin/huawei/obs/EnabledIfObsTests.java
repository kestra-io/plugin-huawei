package io.kestra.plugin.huawei.obs;

import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables OBS tests when either {@code OBS_TESTS=true} (preferred) or the legacy alias
 * {@code OBS_MINIO_TESTS=true} is set in the environment.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@EnabledIf("io.kestra.plugin.huawei.obs.AbstractObsTest#isTestEnabled")
public @interface EnabledIfObsTests {
}
