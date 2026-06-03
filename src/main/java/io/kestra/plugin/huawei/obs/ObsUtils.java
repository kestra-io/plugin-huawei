package io.kestra.plugin.huawei.obs;

/**
 * Static helpers for OBS endpoint resolution, deliberately free of RunContext and SDK imports
 * so they remain unit-testable without bootstrapping Micronaut.
 */
public final class ObsUtils {

    /** Default OBS endpoint template; {@code %s} is the region identifier. */
    public static final String DEFAULT_OBS_ENDPOINT_TEMPLATE = "https://obs.%s.myhuaweicloud.com";

    private ObsUtils() {
    }

    /**
     * Resolves the OBS endpoint URL.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit {@code endpointOverride} — returned as-is (trailing slash stripped).</li>
     *   <li>{@code region} — {@code https://obs.<region>.myhuaweicloud.com}.</li>
     *   <li>Neither set — throws {@link IllegalArgumentException}; OBS has no global fallback endpoint.</li>
     * </ol>
     */
    public static String obsEndpoint(String endpointOverride, String region) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            return String.format(DEFAULT_OBS_ENDPOINT_TEMPLATE, region.trim());
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
