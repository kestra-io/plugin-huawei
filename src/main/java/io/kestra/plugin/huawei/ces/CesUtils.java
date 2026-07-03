package io.kestra.plugin.huawei.ces;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CesUtils {

    // CES namespaces follow the `service.item` format: two dot-separated identifiers, letters/digits/underscores only.
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,31}\\.[A-Za-z][A-Za-z0-9_]{0,31}$");

    private CesUtils() {
    }

    public static void validateNamespaceFormat(String namespace) {
        if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                "namespace '" + namespace + "' is invalid — CES namespaces must follow the `service.item` format " +
                "(e.g. `SYS.ECS` or `MyApp.Custom`), using only letters, digits, and underscores.");
        }
    }

    public static void validateCustomNamespace(String namespace) {
        validateNamespaceFormat(namespace);
        if (namespace.toUpperCase(Locale.ROOT).startsWith("SYS.")) {
            throw new IllegalArgumentException(
                "namespace '" + namespace + "' is invalid for custom metrics — the `SYS.` prefix is reserved for " +
                "Huawei Cloud system namespaces. Use a custom prefix such as `MyApp.Custom` instead.");
        }
    }

    // Resolution order: endpointOverride → region-derived (suffix defaults to myhuaweicloud.com) → throws.
    public static String cesEndpoint(String endpointOverride, String region, String endpointSuffix) {
        if (isNotBlank(endpointOverride)) {
            return stripTrailingSlash(endpointOverride.trim());
        }
        if (isNotBlank(region)) {
            var suffix = isNotBlank(endpointSuffix) ? endpointSuffix.trim() : "myhuaweicloud.com";
            return "https://ces." + region.trim() + "." + suffix;
        }
        throw new IllegalArgumentException(
            "CES requires either `endpointOverride` or `region` to be set — " +
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
