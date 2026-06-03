package io.kestra.plugin.huawei;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for authenticating against Huawei Cloud APIs.
 *
 * <p>Two credential flavours are supported, matching what the issue calls out:
 * <ul>
 *   <li><b>AK/SK</b> — long-lived access key + secret key, used to sign individual API requests. Downstream
 *       service tasks (added in follow-up issues) build their request signers from this pair.</li>
 *   <li><b>Token-based</b> — a short-lived IAM token obtained from {@code GetToken}; downstream tasks send it
 *       verbatim in the {@code X-Auth-Token} header.</li>
 * </ul>
 *
 * <p>This class deliberately holds no state and performs no network I/O — it only resolves endpoints and
 * shapes the JSON bodies that {@link io.kestra.plugin.huawei.auth.GetToken} (and future tasks) POST to IAM.
 */
public final class ConnectionUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default IAM endpoint host template; {@code %s} is the region. */
    public static final String DEFAULT_IAM_ENDPOINT_TEMPLATE = "https://iam.%s.myhuaweicloud.com";

    /** Global IAM endpoint, used when no region is configured. */
    public static final String GLOBAL_IAM_ENDPOINT = "https://iam.myhuaweicloud.com";

    /** Keystone v3 path that issues IAM tokens. The response carries the token in the {@code X-Subject-Token} header. */
    public static final String IAM_TOKEN_PATH = "/v3/auth/tokens";

    private ConnectionUtils() {
    }

    /**
     * Resolves the base IAM endpoint URL for a given client config.
     *
     * <p>Resolution order: explicit override → {@code https://iam.<region>.myhuaweicloud.com} → global endpoint.
     */
    public static String iamEndpoint(final AbstractConnection.HuaweiClientConfig config) {
        if (isNotBlank(config.iamEndpointOverride())) {
            return stripTrailingSlash(config.iamEndpointOverride().trim());
        }
        if (isNotBlank(config.region())) {
            return String.format(DEFAULT_IAM_ENDPOINT_TEMPLATE, config.region().trim());
        }
        return GLOBAL_IAM_ENDPOINT;
    }

    /**
     * Full URL for the Keystone {@code /v3/auth/tokens} endpoint.
     */
    public static String iamTokenUrl(final AbstractConnection.HuaweiClientConfig config) {
        return iamEndpoint(config) + IAM_TOKEN_PATH;
    }

    /**
     * Builds the JSON body for a password-identity token request.
     *
     * <p>{@code scope} is either project-scoped (if {@code projectName} provided) or domain-scoped (if
     * {@code domainName} provided) — pass exactly one. Unscoped tokens are not exposed by this plugin.
     *
     * @param username   IAM user name
     * @param password   IAM user password
     * @param userDomain Account (domain) name the user belongs to
     * @param projectName Project (region) name to scope the token to; may be {@code null}
     * @param domainName  Domain name to scope the token to; may be {@code null}
     * @return JSON body as a String
     */
    public static String passwordTokenRequestBody(
        String username,
        String password,
        String userDomain,
        String projectName,
        String domainName
    ) {
        require(username, "username");
        require(password, "password");
        require(userDomain, "userDomain");

        boolean hasProject = isNotBlank(projectName);
        boolean hasDomain = isNotBlank(domainName);
        if (hasProject == hasDomain) {
            throw new IllegalArgumentException(
                "Exactly one of `projectName` or `domainName` must be set to scope the IAM token."
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> auth = new LinkedHashMap<>();
        body.put("auth", auth);

        Map<String, Object> identity = new LinkedHashMap<>();
        auth.put("identity", identity);
        identity.put("methods", new String[]{"password"});

        Map<String, Object> passwordObj = new LinkedHashMap<>();
        identity.put("password", passwordObj);
        Map<String, Object> user = new LinkedHashMap<>();
        passwordObj.put("user", user);
        user.put("name", username);
        user.put("password", password);
        user.put("domain", Map.of("name", userDomain));

        Map<String, Object> scope = new LinkedHashMap<>();
        auth.put("scope", scope);
        if (hasProject) {
            scope.put("project", Map.of("name", projectName));
        } else {
            scope.put("domain", Map.of("name", domainName));
        }

        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            // Inputs are plain Java maps with String values — the only realistic failure here is OOM, which
            // would surface from the caller anyway. Wrap to keep the API checked-exception free.
            throw new IllegalStateException("Failed to serialize IAM token request body", e);
        }
    }

    /**
     * Parses the {@code expires_at} timestamp out of the IAM token response body without forcing callers to
     * pull in Jackson directly.
     *
     * @return ISO-8601 string from {@code token.expires_at}, or {@code null} if the body cannot be parsed
     */
    public static String parseExpiresAt(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(responseBody);
            ObjectNode token = (ObjectNode) root.get("token");
            if (token == null) {
                return null;
            }
            return token.has("expires_at") ? token.get("expires_at").asText(null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(String value, String name) {
        if (!isNotBlank(value)) {
            throw new IllegalArgumentException("`" + name + "` is required to obtain a Huawei IAM token");
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
