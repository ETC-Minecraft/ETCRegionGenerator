package com.etcmc.regiongenerator.task;

public enum TaskState {
    /** Created, not yet started                    */
    QUEUED,
    /** Actively generating chunks                  */
    RUNNING,
    /** User paused — progress saved, loop halted   */
    PAUSED,
    /** All chunks finished successfully            */
    DONE,
    /** User cancelled — progress discarded         */
    CANCELLED
}
