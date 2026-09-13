package dev.remo.simplebackup.notification;

/** Muss mit dem CHECK-Constraint auf {@code notification_outbox.status} uebereinstimmen. */
enum OutboxStatus {
    PENDING,
    SENT,
    FAILED,
    /** Die Versuche sind erschoepft. Der Eintrag bleibt stehen, damit es auffaellt. */
    ABANDONED
}
