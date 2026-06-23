package io.kestra.plugin.huawei.functiongraph;

/**
 * Thrown when FunctionGraph reports a function-level error (HTTP 200 but the function itself
 * returned an error) or when the invocation fails with a non-2xx HTTP status.
 */
public class FunctionGraphInvokeException extends RuntimeException {

    public FunctionGraphInvokeException(String message) {
        super(message);
    }

    public FunctionGraphInvokeException(String message, Throwable cause) {
        super(message, cause);
    }
}
