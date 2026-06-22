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
     *   <li>{@code region} — {@code https://dataarts.<region>.myhuaweicloud.com}.</li>
     *   <li>Neither set — throws {@link IllegalArgumentException}.</li>
     * </ol>
     */
    public static String dataArtsEndpoint(String endpointOverride, String region) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            return "https://dataarts." + region.trim() + ".myhuaweicloud.com";
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
