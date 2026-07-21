package io.kestra.plugin.huawei.rfs;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RfsUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = RfsUtils.rfsEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://aos.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = RfsUtils.rfsEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://aos.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = RfsUtils.rfsEndpoint("https://custom.rfs.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.rfs.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = RfsUtils.rfsEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = RfsUtils.rfsEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://aos.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> RfsUtils.rfsEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void customEndpointOverride_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> RfsUtils.requireProjectIdForCustomEndpoint("https://custom.rfs.endpoint.com", null, null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpointSuffix_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> RfsUtils.requireProjectIdForCustomEndpoint(null, "myhuaweicloud.eu", null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpoint_withProjectId_doesNotThrow() {
        RfsUtils.requireProjectIdForCustomEndpoint("https://custom.rfs.endpoint.com", null, "project-abc");
    }

    @Test
    void noCustomEndpoint_withoutProjectId_doesNotThrow() {
        RfsUtils.requireProjectIdForCustomEndpoint(null, null, null);
    }
}
