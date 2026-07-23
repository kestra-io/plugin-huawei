package io.kestra.plugin.huawei.mrs;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MrsUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = MrsUtils.mrsEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://mrs.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = MrsUtils.mrsEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://mrs.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = MrsUtils.mrsEndpoint("https://custom.mrs.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.mrs.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = MrsUtils.mrsEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = MrsUtils.mrsEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://mrs.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> MrsUtils.mrsEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void customEndpointOverride_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> MrsUtils.requireProjectIdForCustomEndpoint("https://custom.mrs.endpoint.com", null, null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpointSuffix_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> MrsUtils.requireProjectIdForCustomEndpoint(null, "myhuaweicloud.eu", null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpoint_withProjectId_doesNotThrow() {
        MrsUtils.requireProjectIdForCustomEndpoint("https://custom.mrs.endpoint.com", null, "project-abc");
    }

    @Test
    void noCustomEndpoint_withoutProjectId_doesNotThrow() {
        MrsUtils.requireProjectIdForCustomEndpoint(null, null, null);
    }
}
