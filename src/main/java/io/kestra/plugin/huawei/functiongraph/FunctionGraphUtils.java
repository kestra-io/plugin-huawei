package io.kestra.plugin.huawei.functiongraph;

/**
 * Static helpers for FunctionGraph endpoint resolution, free of RunContext and SDK imports
 * so they remain unit-testable without bootstrapping Micronaut.
 */
public final class FunctionGraphUtils {

    private FunctionGraphUtils() {
    }

    /**
     * Resolves the FunctionGraph endpoint URL.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit {@code endpointOverride} — returned as-is (trailing slash stripped);
     *       {@code endpointSuffix} is ignored.</li>
     *   <li>{@code region} — {@code https://functiongraph.{region}.{suffix}} where {@code suffix}
     *       defaults to {@code myhuaweicloud.com} when blank or null.</li>
     *   <li>Neither set — throws {@link IllegalArgumentException}.</li>
     * </ol>
     */
    public static String functionGraphEndpoint(String endpointOverride, String region, String endpointSuffix) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            var suffix = isNotBlank(endpointSuffix) ? endpointSuffix.trim() : "myhuaweicloud.com";
            return "https://functiongraph." + region.trim() + "." + suffix;
        }
        throw new IllegalArgumentException(
            "FunctionGraph requires either `endpointOverride` or `region` to be set — " +
            "set the 'region' property (e.g. eu-west-101) or provide an explicit 'endpointOverride'."
        );
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
