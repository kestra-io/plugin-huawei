package io.kestra.plugin.huawei.rfs;

import java.util.Map;

/**
 * Static helpers for RFS endpoint resolution and project-id validation, deliberately free of
 * RunContext and SDK imports so they remain unit-testable without bootstrapping Micronaut.
 */
public final class RfsUtils {

    private RfsUtils() {
    }

    /**
     * Sovereign regions whose SDK-baked {@code AosRegion} endpoint is wrong and must be derived
     * ourselves, keyed to the correct domain suffix. The AOS SDK hard-codes {@code eu-west-101} to
     * {@code https://aos.myhuaweicloud.eu} — that host lacks the region segment and does not resolve;
     * the working host is {@code rfs.eu-west-101.myhuaweicloud.eu}, matching every other region's
     * {@code rfs.<region>.<suffix>} pattern.
     */
    private static final Map<String, String> SOVEREIGN_SUFFIXES = Map.of("eu-west-101", "myhuaweicloud.eu");

    /** Whether {@code region} is a sovereign region whose SDK endpoint we must override with our own derivation. */
    public static boolean isSovereignRegion(String region) {
        return region != null && SOVEREIGN_SUFFIXES.containsKey(region.trim());
    }

    // Resolution order: endpointOverride → region-derived → throws. The host prefix is always `rfs.`
    // (the actual RFS API host; `aos` is only the SDK artifact's internal service name). The suffix
    // defaults to a sovereign region's known suffix when applicable, else myhuaweicloud.com.
    public static String rfsEndpoint(String endpointOverride, String region, String endpointSuffix) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            var r = region.trim();
            var suffix = isNotBlank(endpointSuffix)
                ? endpointSuffix.trim()
                : SOVEREIGN_SUFFIXES.getOrDefault(r, "myhuaweicloud.com");
            return "https://rfs." + r + "." + suffix;
        }
        throw new IllegalArgumentException(
            "RFS requires either `endpointOverride` or `region` to be set — " +
            "set the 'region' property (e.g. eu-west-101) or provide an explicit 'endpointOverride'."
        );
    }

    /**
     * The RFS v1 APIs embed the project id in the request path (`/v1/{project_id}/stacks/...`). When
     * the SDK resolves the endpoint from its region enum it can auto-discover the project, but a
     * custom endpoint (endpointOverride or endpointSuffix — e.g. sovereign clouds) bypasses that,
     * leaving `{project_id}` unsubstituted and the gateway rejecting the call with an opaque 400.
     * Fail fast with an actionable message instead.
     */
    public static void requireProjectIdForCustomEndpoint(String rOverride, String rSuffix, String projectId) {
        var customEndpoint = isNotBlank(rOverride) || isNotBlank(rSuffix);
        if (customEndpoint && !isNotBlank(projectId)) {
            throw new IllegalArgumentException(
                "RFS requires `projectId` when a custom endpoint (`endpointOverride` or `endpointSuffix`) is used — " +
                "set the 'projectId' property to your Huawei Cloud project ID for the target region " +
                "(found in the console under 'My Credentials' → 'API Credentials').");
        }
    }

    static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
