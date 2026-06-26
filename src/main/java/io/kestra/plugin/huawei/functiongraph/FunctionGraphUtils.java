package io.kestra.plugin.huawei.functiongraph;

public final class FunctionGraphUtils {

    private FunctionGraphUtils() {
    }

    // Resolution order: endpointOverride → region-derived (suffix defaults to myhuaweicloud.com) → throws.
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

    static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
