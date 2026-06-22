package io.kestra.plugin.huawei.dataarts;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataArtsUtilsTest {

    // ── DataArtsService.isTerminalState ──────────────────────────────────────────

    @Test
    void isTerminalState_null_returnsFalse() {
        assertThat(DataArtsService.isTerminalState(null), equalTo(false));
    }

    @Test
    void isTerminalState_knownTerminalStatuses_returnTrue() {
        for (var status : new String[]{"success", "fail", "running-exception", "manual-stop"}) {
            assertThat("expected terminal for: " + status, DataArtsService.isTerminalState(status), equalTo(true));
        }
    }

    @Test
    void isTerminalState_nonTerminalStatuses_returnFalse() {
        for (var status : new String[]{"waiting", "running", "pause"}) {
            assertThat("expected non-terminal for: " + status, DataArtsService.isTerminalState(status), equalTo(false));
        }
    }

    @Test
    void endpointOverride_winsOverRegion() {
        var endpoint = DataArtsUtils.dataArtsEndpoint("https://custom.dataarts.endpoint.com", "eu-west-101");
        assertThat(endpoint, equalTo("https://custom.dataarts.endpoint.com"));
    }

    @Test
    void endpointOverride_trailingSlashStripped() {
        var endpoint = DataArtsUtils.dataArtsEndpoint("http://localhost:8080/", null);
        assertThat(endpoint, equalTo("http://localhost:8080"));
    }

    @Test
    void region_derivesComTldEndpoint() {
        var endpoint = DataArtsUtils.dataArtsEndpoint(null, "cn-north-4");
        assertThat(endpoint, equalTo("https://dataarts.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_euWest_derivesEndpoint() {
        var endpoint = DataArtsUtils.dataArtsEndpoint(null, "eu-west-101");
        assertThat(endpoint, equalTo("https://dataarts.eu-west-101.myhuaweicloud.com"));
    }

    @Test
    void region_withTrailingWhitespace_derivesCorrectEndpoint() {
        var endpoint = DataArtsUtils.dataArtsEndpoint(null, "  ap-southeast-1  ");
        assertThat(endpoint, equalTo("https://dataarts.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = DataArtsUtils.dataArtsEndpoint("   ", "ap-southeast-1");
        assertThat(endpoint, equalTo("https://dataarts.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void neitherSet_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> DataArtsUtils.dataArtsEndpoint(null, null));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
        assertThat(ex.getMessage().contains("region"), equalTo(true));
    }

    @Test
    void blankBoth_throwsWithClearMessage() {
        var ex = assertThrows(IllegalArgumentException.class, () -> DataArtsUtils.dataArtsEndpoint("  ", "  "));
        assertThat(ex.getMessage().contains("endpointOverride"), equalTo(true));
    }
}
