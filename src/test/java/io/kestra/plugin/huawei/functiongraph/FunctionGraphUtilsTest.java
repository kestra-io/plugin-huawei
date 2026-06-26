package io.kestra.plugin.huawei.functiongraph;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionGraphUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://functiongraph.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_euWest_derivesEndpoint() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint(null, "eu-west-101", null);
        assertThat(endpoint, equalTo("https://functiongraph.eu-west-101.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://functiongraph.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint("https://custom.fg.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.fg.endpoint.com"));
    }

    @Test
    void endpointOverride_winsOverRegionAndSuffix() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint("https://custom.fg.endpoint.com", "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://custom.fg.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void region_withTrailingWhitespace_derivesCorrectEndpoint() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint(null, "  ap-southeast-1  ", null);
        assertThat(endpoint, equalTo("https://functiongraph.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://functiongraph.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FunctionGraphUtils.functionGraphEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void blankBoth_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FunctionGraphUtils.functionGraphEndpoint("  ", "  ", null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
    }

    @Test
    void blankSuffix_defaultsToComTld() {
        var endpoint = FunctionGraphUtils.functionGraphEndpoint(null, "eu-west-101", "  ");
        assertThat(endpoint, equalTo("https://functiongraph.eu-west-101.myhuaweicloud.com"));
    }
}
