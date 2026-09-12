package dev.remo.simplebackup.run;

/** Muss mit dem CHECK-Constraint auf {@code run_step.status} uebereinstimmen. */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    /** Uebersprungen, etwa weil ein vorausgehender Schritt gescheitert ist. */
    SKIPPED,
    CANCELLED,
    TIMEOUT
}
