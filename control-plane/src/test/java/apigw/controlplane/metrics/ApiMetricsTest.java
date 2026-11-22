package apigw.controlplane.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiMetricsTest {

    @Test
    void endpointLabelsComeFromAFixedVocabulary() {
        assertThat(ApiMetrics.endpoint("/admin/v1/routes")).isEqualTo("routes");
        assertThat(ApiMetrics.endpoint("/admin/v1/routes/r-1")).isEqualTo("routes");
        assertThat(ApiMetrics.endpoint("/admin/v1/changesets/abc/publish")).isEqualTo("changesets.publish");
        assertThat(ApiMetrics.endpoint("/admin/v1/history/12/rollback")).isEqualTo("history.rollback");
        assertThat(ApiMetrics.endpoint("/admin/v1/plugins/jwt/schema")).isEqualTo("plugins.schema");
        assertThat(ApiMetrics.endpoint("/ready")).isEqualTo("ready");
        // Unknown or hostile paths never become label values.
        assertThat(ApiMetrics.endpoint("/admin/v1/<script>/x")).isEqualTo("other");
        assertThat(ApiMetrics.endpoint("/admin/v1/routes/x/" + "a".repeat(500))).isEqualTo("routes");
        assertThat(ApiMetrics.endpoint("/etc/passwd")).isEqualTo("other");
        assertThat(ApiMetrics.endpoint(null)).isEqualTo("other");
    }
}
