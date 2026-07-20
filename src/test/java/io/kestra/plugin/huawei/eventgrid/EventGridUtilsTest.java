package io.kestra.plugin.huawei.eventgrid;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventGridUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = EventGridUtils.egEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://eg.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = EventGridUtils.egEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://eg.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = EventGridUtils.egEndpoint("https://custom.eg.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.eg.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = EventGridUtils.egEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = EventGridUtils.egEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://eg.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> EventGridUtils.egEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void customEndpointOverride_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> EventGridUtils.requireProjectIdForCustomEndpoint("https://custom.eg.endpoint.com", null, null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpointSuffix_withoutProjectId_throws() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> EventGridUtils.requireProjectIdForCustomEndpoint(null, "myhuaweicloud.eu", null));
        assertThat(ex.getMessage().contains("projectId"), equalTo(true));
    }

    @Test
    void customEndpoint_withProjectId_doesNotThrow() {
        EventGridUtils.requireProjectIdForCustomEndpoint("https://custom.eg.endpoint.com", null, "project-abc");
    }

    @Test
    void noCustomEndpoint_withoutProjectId_doesNotThrow() {
        EventGridUtils.requireProjectIdForCustomEndpoint(null, null, null);
    }
}
