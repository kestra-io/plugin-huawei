package io.kestra.plugin.huawei.dis;

import io.kestra.plugin.huawei.AbstractConnection;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link DisService#buildClient} endpoint resolution, and specifically that the `projectId`
 * fail-fast keys off the <em>resolved</em> endpoint rather than the input properties: a region absent
 * from the SDK's `DisRegion` enum resolves to `withEndpoint(...)` without `endpointOverride` or
 * `endpointSuffix` being set, and previously slipped past the guard.
 */
class DisServiceTest {

    private static AbstractConnection.HuaweiClientConfig config(String projectId, String region) {
        return new AbstractConnection.HuaweiClientConfig("ak", "sk", null, projectId, null, region);
    }

    @Test
    void regionNotInSdkEnum_withoutProjectId_failsFast() {
        // ap-southeast-3 is NOT in DisRegion, so buildClient falls back to a derived endpoint.
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DisService.buildClient(config(null, "ap-southeast-3"), null, "ap-southeast-3", null));

        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
        assertThat(ex.getMessage().contains("ap-southeast-3"), equalTo(true));
    }

    @Test
    void regionNotInSdkEnum_withProjectId_buildsClient() {
        var client = DisService.buildClient(
            config("project-abc", "ap-southeast-3"), null, "ap-southeast-3", null);

        assertThat(client, notNullValue());
    }

    // NOTE: there is deliberately no `regionInSdkEnum_withoutProjectId` case here. For a region the SDK
    // enum knows, omitting projectId makes the SDK auto-discover it by calling IAM while the client is
    // being built — a live network call, which is exactly the behaviour the guard exists to preserve but
    // is not something a unit test should perform. That the guard stays silent for a non-custom endpoint
    // is covered offline by DisUtilsTest#noCustomEndpoint_withoutProjectId_doesNotThrow.

    @Test
    void explicitEndpointSuffix_withoutProjectId_failsFast() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DisService.buildClient(config(null, "cn-north-4"), null, "cn-north-4", "myhuaweicloud.eu"));

        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void explicitEndpointOverride_withoutProjectId_failsFast() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DisService.buildClient(config(null, null), "https://custom.dis.endpoint.com", null, null));

        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void neitherEndpointNorRegion_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DisService.buildClient(config("project-abc", null), null, null, null));

        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }
}
