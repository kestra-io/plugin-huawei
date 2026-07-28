package io.kestra.plugin.huawei.dataarts;

/**
 * Static helpers for DataArts Studio endpoint resolution, free of RunContext and SDK imports
 * so they remain unit-testable without bootstrapping Micronaut.
 */
public final class DataArtsUtils {

    private DataArtsUtils() {
    }

    /**
     * Resolves the DataArts Studio endpoint URL.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit {@code endpointOverride} — returned as-is (trailing slash stripped).</li>
     *   <li>{@code region} — {@code https://dayu.<region>.myhuaweicloud.com}.</li>
     *   <li>Neither set — throws {@link IllegalArgumentException}.</li>
     * </ol>
     *
     * <p>The host prefix is {@code dayu}, not {@code dataarts}: {@code DataArtsStudioRegion} in
     * {@code huaweicloud-sdk-dataartsstudio} maps all 26 of its regions to
     * {@code https://dayu.<region>.myhuaweicloud.com}, with no {@code dataarts.} host anywhere.
     * {@code dataarts.<region>} happens to resolve in most regions but not all — notably
     * {@code dataarts.tr-west-1.myhuaweicloud.com} has no DNS record — so the previous derivation
     * failed outright in Turkey. See {@code AGENTS.md} on Huawei host prefixes differing from the
     * SDK artifact name.
     */
    public static String dataArtsEndpoint(String endpointOverride, String region) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            return "https://dayu." + region.trim() + ".myhuaweicloud.com";
        }
        throw new IllegalArgumentException(
            "DataArts Studio requires either `endpointOverride` or `region` to be set — " +
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
