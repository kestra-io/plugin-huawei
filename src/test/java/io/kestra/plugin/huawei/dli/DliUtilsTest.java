package io.kestra.plugin.huawei.dli;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DliUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = DliUtils.dliEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://dli.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = DliUtils.dliEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://dli.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = DliUtils.dliEndpoint("https://custom.dli.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.dli.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = DliUtils.dliEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = DliUtils.dliEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://dli.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> DliUtils.dliEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void customEndpointOverride_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DliUtils.requireProjectIdForCustomEndpoint("https://custom.dli.endpoint.com", null, null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpointSuffix_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> DliUtils.requireProjectIdForCustomEndpoint(null, "myhuaweicloud.eu", null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpoint_withProjectId_doesNotThrow() {
        DliUtils.requireProjectIdForCustomEndpoint("https://custom.dli.endpoint.com", null, "project-abc");
    }

    @Test
    void noCustomEndpoint_withoutProjectId_doesNotThrow() {
        DliUtils.requireProjectIdForCustomEndpoint(null, null, null);
    }
}
