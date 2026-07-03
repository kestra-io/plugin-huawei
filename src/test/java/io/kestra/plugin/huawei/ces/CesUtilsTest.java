package io.kestra.plugin.huawei.ces;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CesUtilsTest {

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = CesUtils.cesEndpoint(null, "cn-north-4", null);
        assertThat(endpoint, equalTo("https://ces.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_withEuSuffix_derivesEuEndpoint() {
        var endpoint = CesUtils.cesEndpoint(null, "eu-west-101", "myhuaweicloud.eu");
        assertThat(endpoint, equalTo("https://ces.eu-west-101.myhuaweicloud.eu"));
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = CesUtils.cesEndpoint("https://custom.ces.endpoint.com", "eu-west-101", null);
        assertThat(endpoint, equalTo("https://custom.ces.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = CesUtils.cesEndpoint("http://localhost:8080/", null, null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = CesUtils.cesEndpoint("   ", "ap-southeast-1", null);
        assertThat(endpoint, equalTo("https://ces.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> CesUtils.cesEndpoint(null, null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void validNamespace_doesNotThrow() {
        CesUtils.validateNamespaceFormat("SYS.ECS");
        CesUtils.validateNamespaceFormat("MyApp.Custom");
    }

    @Test
    void invalidNamespace_missingDot_throws() {
        var ex = assertThrows(IllegalArgumentException.class, () -> CesUtils.validateNamespaceFormat("SYSECS"));
        assertThat(ex.getMessage().contains("service.item"), equalTo(true));
    }

    @Test
    void invalidNamespace_startsWithDigit_throws() {
        assertThrows(IllegalArgumentException.class, () -> CesUtils.validateNamespaceFormat("1App.Custom"));
    }

    @Test
    void customNamespace_sysPrefix_throws() {
        var ex = assertThrows(IllegalArgumentException.class, () -> CesUtils.validateCustomNamespace("SYS.ECS"));
        assertThat(ex.getMessage().contains("SYS."), equalTo(true));
    }

    @Test
    void customNamespace_sysPrefixCaseInsensitive_throws() {
        assertThrows(IllegalArgumentException.class, () -> CesUtils.validateCustomNamespace("sys.custom"));
    }

    @Test
    void customNamespace_validCustom_doesNotThrow() {
        CesUtils.validateCustomNamespace("MyApp.Custom");
    }
}
