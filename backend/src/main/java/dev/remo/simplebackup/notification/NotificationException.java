package dev.remo.simplebackup.notification;

/** Eine Zustellung ist gescheitert. Die Meldung landet im Postausgang und wird wiederholt. */
class NotificationException extends RuntimeException {

    NotificationException(String message) {
        super(message);
    }

    NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
