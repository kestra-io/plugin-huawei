package io.kestra.plugin.huawei.mrs;

/** MRS job execution type, mapped to the `job_type` field of a job step. */
public enum JobType {
    MAP_REDUCE("MapReduce"),
    SPARK_SUBMIT("SparkSubmit"),
    HIVE_SCRIPT("HiveScript"),
    HIVE_SQL("HiveSql"),
    DIST_CP("DistCp"),
    SPARK_SCRIPT("SparkScript"),
    SPARK_SQL("SparkSql"),
    FLINK("Flink");

    private final String value;

    JobType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
