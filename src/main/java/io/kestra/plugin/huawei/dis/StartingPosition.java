package io.kestra.plugin.huawei.dis;

/**
 * Where a partition cursor starts reading from when no resume watermark is available yet.
 * Mirrors DIS's {@code ShowCursorRequest.CursorTypeEnum}, exposing only the subset relevant to a
 * first read (resuming from a sequence number is handled internally via {@code AFTER_SEQUENCE_NUMBER}).
 */
public enum StartingPosition {
    /** Read from the oldest available record still within the stream's retention period. */
    TRIM_HORIZON,
    /** Read only records produced after the cursor is created. */
    LATEST,
    /** Read from the oldest record at or after {@code startingTimestamp}. */
    AT_TIMESTAMP
}
