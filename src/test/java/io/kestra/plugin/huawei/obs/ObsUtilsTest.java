package io.kestra.plugin.huawei.obs;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObsUtilsTest {

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = ObsUtils.obsEndpoint("https://custom.obs.endpoint.com", "eu-west-101");
        assertThat(endpoint, equalTo("https://custom.obs.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = ObsUtils.obsEndpoint("http://localhost:9000/", null);
        assertThat(endpoint, equalTo("http://localhost:9000"));
    }

    @Test
    void region_derivesDefaultEndpoint() {
        var endpoint = ObsUtils.obsEndpoint(null, "eu-west-101");
        assertThat(endpoint, equalTo("https://obs.eu-west-101.myhuaweicloud.com"));
    }

    @Test
    void region_withTrailingWhitespace_derivesCorrectEndpoint() {
        var endpoint = ObsUtils.obsEndpoint(null, "  cn-north-4  ");
        assertThat(endpoint, equalTo("https://obs.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ObsUtils.obsEndpoint(null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = ObsUtils.obsEndpoint("   ", "ap-southeast-1");
        assertThat(endpoint, equalTo("https://obs.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void blankBoth_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ObsUtils.obsEndpoint("  ", "  "));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
    }
}
