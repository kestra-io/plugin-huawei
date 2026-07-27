package io.kestra.plugin.huawei.swr;

/**
 * Static helpers for SWR endpoint resolution, deliberately free of RunContext and SDK imports so
 * they remain unit-testable without bootstrapping Micronaut.
 */
public final class SwrUtils {

    private SwrUtils() {
    }

    // Resolution order: endpointOverride → region-derived (suffix defaults to myhuaweicloud.com) → throws.
    public static String swrEndpoint(String endpointOverride, String region, String endpointSuffix) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            var suffix = isNotBlank(endpointSuffix) ? endpointSuffix.trim() : "myhuaweicloud.com";
            return "https://swr." + region.trim() + "." + suffix;
        }
        throw new IllegalArgumentException(
            "SWR requires either `endpointOverride` or `region` to be set — " +
            "set the 'region' property (e.g. eu-west-101) or provide an explicit 'endpointOverride'."
        );
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
