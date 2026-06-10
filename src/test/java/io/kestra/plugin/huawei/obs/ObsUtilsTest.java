package io.kestra.plugin.huawei.obs;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObsUtilsTest {

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = ObsUtils.obsEndpoint("https://custom.obs.endpoint.com", "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://custom.obs.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = ObsUtils.obsEndpoint("http://localhost:9000/", null, null);
        assertThat(endpoint, equalTo("http://localhost:9000"));
    }

    @Test
    void endpointOverride_suffixIgnored() {
        // endpointOverride wins regardless of what endpointSuffix is set to
        var endpoint = ObsUtils.obsEndpoint("https://my.override.com", "cn-north-4", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://my.override.com"));
    }

    @Test
    void region_defaultSuffix_derivesComTldEndpoint() {
        var endpoint = ObsUtils.obsEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://obs.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_explicitComSuffix_derivesComTldEndpoint() {
        var endpoint = ObsUtils.obsEndpoint(null, "ap-southeast-1", "myhuaweicloud.com");
        assertThat(endpoint, equalTo("https://obs.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void region_euSuffix_derivesEuTldEndpoint() {
        var endpoint = ObsUtils.obsEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://obs.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void region_blankSuffix_fallsBackToComTld() {
        var endpoint = ObsUtils.obsEndpoint(null, "eu-west-101", "   ");
        assertThat(endpoint, equalTo("https://obs.eu-west-101.myhuaweicloud.com"));
    }

    @Test
    void region_withTrailingWhitespace_derivesCorrectEndpoint() {
        var endpoint = ObsUtils.obsEndpoint(null, "  cn-north-4  ", null);
        assertThat(endpoint, equalTo("https://obs.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = ObsUtils.obsEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://obs.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ObsUtils.obsEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void blankBoth_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ObsUtils.obsEndpoint("  ", "  ", null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
    }
}
