package io.kestra.plugin.huawei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void iamEndpoint_usesOverrideWhenProvided() {
        AbstractConnection.HuaweiClientConfig config = new AbstractConnection.HuaweiClientConfig(
            null, null, null, null, null, "eu-west-101", "https://iam.private.example.com/"
        );

        assertThat(ConnectionUtils.iamEndpoint(config), equalTo("https://iam.private.example.com"));
    }

    @Test
    void iamEndpoint_buildsFromRegionWhenNoOverride() {
        AbstractConnection.HuaweiClientConfig config = new AbstractConnection.HuaweiClientConfig(
            null, null, null, null, null, "ap-southeast-1", null
        );

        assertThat(ConnectionUtils.iamEndpoint(config), equalTo("https://iam.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void iamEndpoint_fallsBackToGlobalWhenNoRegionOrOverride() {
        AbstractConnection.HuaweiClientConfig config = new AbstractConnection.HuaweiClientConfig(
            null, null, null, null, null, null, null
        );

        assertThat(ConnectionUtils.iamEndpoint(config), equalTo(ConnectionUtils.GLOBAL_IAM_ENDPOINT));
    }

    @Test
    void iamTokenUrl_appendsKeystonePath() {
        AbstractConnection.HuaweiClientConfig config = new AbstractConnection.HuaweiClientConfig(
            null, null, null, null, null, "eu-west-101", null
        );

        assertThat(
            ConnectionUtils.iamTokenUrl(config),
            equalTo("https://iam.eu-west-101.myhuaweicloud.com/v3/auth/tokens")
        );
    }

    @Test
    void passwordTokenRequestBody_buildsProjectScopedPayload() throws Exception {
        String body = ConnectionUtils.passwordTokenRequestBody(
            "alice", "p4ss", "acme-corp", "eu-west-101", null
        );

        JsonNode root = MAPPER.readTree(body);
        JsonNode user = root.path("auth").path("identity").path("password").path("user");
        assertThat(user.path("name").asText(), equalTo("alice"));
        assertThat(user.path("password").asText(), equalTo("p4ss"));
        assertThat(user.path("domain").path("name").asText(), equalTo("acme-corp"));

        JsonNode methods = root.path("auth").path("identity").path("methods");
        assertThat(methods.isArray(), is(true));
        assertThat(methods.get(0).asText(), equalTo("password"));

        assertThat(root.path("auth").path("scope").path("project").path("name").asText(), equalTo("eu-west-101"));
        assertThat(root.path("auth").path("scope").path("domain").isMissingNode(), is(true));
    }

    @Test
    void passwordTokenRequestBody_buildsDomainScopedPayload() throws Exception {
        String body = ConnectionUtils.passwordTokenRequestBody(
            "alice", "p4ss", "acme-corp", null, "acme-corp"
        );

        JsonNode scope = MAPPER.readTree(body).path("auth").path("scope");
        assertThat(scope.path("domain").path("name").asText(), equalTo("acme-corp"));
        assertThat(scope.path("project").isMissingNode(), is(true));
    }

    @Test
    void passwordTokenRequestBody_rejectsAmbiguousScope() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionUtils.passwordTokenRequestBody("a", "b", "c", "p", "d")
        );
    }

    @Test
    void passwordTokenRequestBody_rejectsMissingScope() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionUtils.passwordTokenRequestBody("a", "b", "c", null, null)
        );
    }

    @Test
    void passwordTokenRequestBody_rejectsMissingMandatoryFields() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionUtils.passwordTokenRequestBody(null, "b", "c", "p", null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionUtils.passwordTokenRequestBody("a", "", "c", "p", null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionUtils.passwordTokenRequestBody("a", "b", "  ", "p", null)
        );
    }

    @Test
    void parseExpiresAt_returnsTimestampWhenPresent() {
        String body = "{\"token\":{\"expires_at\":\"2026-05-21T10:00:00.000000Z\",\"methods\":[\"password\"]}}";

        assertThat(ConnectionUtils.parseExpiresAt(body), equalTo("2026-05-21T10:00:00.000000Z"));
    }

    @Test
    void parseExpiresAt_returnsNullForUnusableBodies() {
        assertThat(ConnectionUtils.parseExpiresAt(null), is(nullValue()));
        assertThat(ConnectionUtils.parseExpiresAt(""), is(nullValue()));
        assertThat(ConnectionUtils.parseExpiresAt("not json"), is(nullValue()));
        assertThat(ConnectionUtils.parseExpiresAt("{\"token\":{}}"), is(nullValue()));
    }
}
