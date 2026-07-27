package io.kestra.plugin.huawei.mrs;

/**
 * Static helpers for MRS endpoint resolution and project-id validation, deliberately free of
 * RunContext and SDK imports so they remain unit-testable without bootstrapping Micronaut.
 */
public final class MrsUtils {

    private MrsUtils() {
    }

    // Resolution order: endpointOverride → region-derived (suffix defaults to myhuaweicloud.com) → throws.
    public static String mrsEndpoint(String endpointOverride, String region, String endpointSuffix) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            var suffix = isNotBlank(endpointSuffix) ? endpointSuffix.trim() : "myhuaweicloud.com";
            return "https://mrs." + region.trim() + "." + suffix;
        }
        throw new IllegalArgumentException(
            "MRS requires either `endpointOverride` or `region` to be set — " +
            "set the 'region' property (e.g. eu-west-101) or provide an explicit 'endpointOverride'."
        );
    }

    /**
     * MRS's v1 and v2 APIs embed the project id in the request path (e.g.
     * `/v2/{project_id}/run-job-flow`). When the SDK resolves the endpoint from its region enum it
     * can auto-discover the project, but a custom endpoint (endpointOverride or endpointSuffix — e.g.
     * sovereign clouds) bypasses that, leaving `{project_id}` unsubstituted and the gateway rejecting
     * the call with an opaque 400. Fail fast with an actionable message instead.
     */
    public static void requireProjectIdForCustomEndpoint(String rOverride, String rSuffix, String projectId) {
        var customEndpoint = isNotBlank(rOverride) || isNotBlank(rSuffix);
        if (customEndpoint && !isNotBlank(projectId)) {
            throw new IllegalArgumentException(
                "MRS requires `projectId` when a custom endpoint (`endpointOverride` or `endpointSuffix`) is used — " +
                "set the 'projectId' property to your Huawei Cloud project ID for the target region " +
                "(found in the console under 'My Credentials' → 'API Credentials').");
        }
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
