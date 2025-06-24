package com.julemoran.smooth_web.scan;

/**
 * Interface for a scanning task.
 * Implementations will perform the actual file scanning and hashing.
 * The task itself runs synchronously; its asynchronous execution is managed by the calling service.
 */
public interface IScanTask {

    /**
     * Executes the scan.
     * This method performs the scan synchronously.
     * It should handle its own logic for checking the abort status frequently.
     *
     * @return A ScanResult upon completion, failure, or abort.
     */
    ScanResult runScan();

    /**
     * Signals the task that an abort has been requested.
     * The task should attempt to terminate gracefully.
     */
    void requestAbort();

    /**
     * Checks if an abort has been requested for this task.
     * Useful for the task to periodically check if it should stop.
     * @return true if an abort has been requested, false otherwise.
     */
    boolean isAbortRequested();
}
