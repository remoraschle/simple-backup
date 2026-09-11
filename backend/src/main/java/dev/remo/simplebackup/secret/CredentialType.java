package dev.remo.simplebackup.secret;

/** Muss mit dem CHECK-Constraint auf {@code credential.type} uebereinstimmen. */
public enum CredentialType {
    PASSWORD,
    API_TOKEN,
    SSH_PRIVATE_KEY,
    S3_KEYPAIR,
    RESTIC_REPOSITORY_PASSWORD,
    PUSHOVER
}
