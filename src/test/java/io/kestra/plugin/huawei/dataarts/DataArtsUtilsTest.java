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

    /**
     * The status enum, per the SDK's {@code JobInstance$StatusEnum}, is exactly {@code waiting},
     * {@code running}, {@code success}, {@code fail}, {@code manual}, {@code pause}, {@code skip},
     * {@code freeze}. Of those only {@code success}, {@code fail} and {@code skip} are terminal.
     */
    @Test
    void isTerminalState_knownTerminalStatuses_returnTrue() {
        for (var status : new String[]{"success", "fail", "skip"}) {
            assertThat("expected terminal for: " + status, DataArtsService.isTerminalState(status), equalTo(true));
        }
    }

    @Test
    void isTerminalState_nonTerminalStatuses_returnFalse() {
        // manual/pause/freeze await an operator, so they are deliberately non-terminal.
        for (var status : new String[]{"waiting", "running", "manual", "pause", "freeze"}) {
            assertThat("expected non-terminal for: " + status, DataArtsService.isTerminalState(status), equalTo(false));
        }
    }

    /**
     * {@code running-exception} and {@code manual-stop} were asserted as terminal here and in
     * {@code DataArtsTasksTest} but are not in the SDK's enum at all, so they can never arrive on the
     * wire. Likewise {@code forceSuccess}/{@code ignoreSuccess}: {@code force_success} and
     * {@code ignore_success} are separate <em>boolean</em> fields on the instance, never statuses.
     */
    @Test
    void isTerminalState_valuesOutsideTheSdkEnum_returnFalse() {
        for (var status : new String[]{"running-exception", "manual-stop", "skip-by-depend",
                                      "forceSuccess", "ignoreSuccess"}) {
            assertThat("not a real status: " + status, DataArtsService.isTerminalState(status), equalTo(false));
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
        assertThat(endpoint, equalTo("https://dayu.cn-north-4.myhuaweicloud.com"));
    }

    @Test
    void region_euWest_derivesEndpoint() {
        var endpoint = DataArtsUtils.dataArtsEndpoint(null, "eu-west-101");
        assertThat(endpoint, equalTo("https://dayu.eu-west-101.myhuaweicloud.com"));
    }

    @Test
    void region_withTrailingWhitespace_derivesCorrectEndpoint() {
        var endpoint = DataArtsUtils.dataArtsEndpoint(null, "  ap-southeast-1  ");
        assertThat(endpoint, equalTo("https://dayu.ap-southeast-1.myhuaweicloud.com"));
    }

    @Test
    void blankEndpointOverride_fallsBackToRegion() {
        var endpoint = DataArtsUtils.dataArtsEndpoint("   ", "ap-southeast-1");
        assertThat(endpoint, equalTo("https://dayu.ap-southeast-1.myhuaweicloud.com"));
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
