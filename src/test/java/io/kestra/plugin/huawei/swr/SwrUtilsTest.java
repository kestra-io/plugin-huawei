package io.kestra.plugin.huawei.swr;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SwrUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = SwrUtils.swrEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://swr.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = SwrUtils.swrEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://swr.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = SwrUtils.swrEndpoint("https://custom.swr.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.swr.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = SwrUtils.swrEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = SwrUtils.swrEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://swr.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> SwrUtils.swrEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }
}
