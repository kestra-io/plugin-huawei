package io.kestra.plugin.huawei.dis;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = DisUtils.disEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://dis.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = DisUtils.disEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://dis.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = DisUtils.disEndpoint("https://custom.dis.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.dis.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = DisUtils.disEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = DisUtils.disEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://dis.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> DisUtils.disEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void customEndpointOverride_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DisUtils.requireProjectIdForCustomEndpoint("https://custom.dis.endpoint.com", null, null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpointSuffix_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DisUtils.requireProjectIdForCustomEndpoint(null, "myhuaweicloud.eu", null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpoint_withProjectId_doesNotThrow() {
        DisUtils.requireProjectIdForCustomEndpoint("https://custom.dis.endpoint.com", null, "project-abc");
    }

    @Test
    void noCustomEndpoint_withoutProjectId_doesNotThrow() {
        DisUtils.requireProjectIdForCustomEndpoint(null, null, null);
    }
}
