package io.kestra.plugin.huawei.obs;

/**
 * Static helpers for OBS endpoint resolution, deliberately free of RunContext and SDK imports
 * so they remain unit-testable without bootstrapping Micronaut.
 */
public final class ObsUtils {

    private ObsUtils() {
    }

    /**
     * Resolves the OBS endpoint URL.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit {@code endpointOverride} — returned as-is (trailing slash stripped); {@code endpointSuffix} is ignored.</li>
     *   <li>{@code region} — {@code https://obs.<region>.<suffix>}, where {@code suffix} defaults to
     *       {@code myhuaweicloud.com} when blank or null.</li>
     *   <li>Neither set — throws {@link IllegalArgumentException}; OBS has no global fallback endpoint.</li>
     * </ol>
     */
    public static String obsEndpoint(String endpointOverride, String region, String endpointSuffix) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            var suffix = isNotBlank(endpointSuffix) ? endpointSuffix.trim() : "myhuaweicloud.com";
            return "https://obs." + region.trim() + "." + suffix;
        }
        throw new IllegalArgumentException(
            "OBS requires either `endpointOverride` or `region` to be set. " +
            "Unlike IAM, OBS has no global fallback endpoint."
        );
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
