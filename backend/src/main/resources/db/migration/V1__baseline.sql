-- Grundschema simple-backup.
--
-- Konventionen:
--   * Enums als TEXT mit CHECK-Constraint statt PostgreSQL-ENUM-Typen. Ein neuer Wert
--     ist damit eine gewoehnliche Migration statt ALTER TYPE mit Sperrverhalten.
--   * Adapterspezifische Konfiguration als JSONB. Ein neuer Quell- oder Zieltyp braucht
--     dadurch keine Schemaaenderung; validiert wird typisiert in Java.
--   * Alle Zeitstempel mit Zeitzone. Backups laufen ueber Sommerzeitwechsel hinweg.

-- gen_random_uuid() gehoert seit PostgreSQL 13 zum Kern; die Erweiterung pgcrypto wird
-- dafuer nicht mehr gebraucht. Das erspart der Anwendungsdatenbank Superuser-Rechte.

-- ---------------------------------------------------------------- Benutzer

CREATE TABLE app_user (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    username             text        NOT NULL UNIQUE,
    password_hash        text        NOT NULL,
    role                 text        NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    must_change_password boolean     NOT NULL DEFAULT false,
    enabled              boolean     NOT NULL DEFAULT true,
    failed_login_count   integer     NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    last_login_at        timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    username    text,
    action      text        NOT NULL,
    entity_type text,
    entity_id   text,
    detail      jsonb,
    client_ip   text
);

CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at DESC);

-- ------------------------------------------------------------ Zugangsdaten
--
-- Envelope-Verschluesselung: Pro Datensatz wird ein zufaelliger Data Key (DEK)
-- erzeugt, der Klartext damit AES-256-GCM verschluesselt und der DEK seinerseits
-- mit dem Masterkey (KEK) gewrappt. Der Masterkey steht ausschliesslich in der
-- Umgebung, niemals in dieser Datenbank. key_version erlaubt Rotation, ohne dass
-- alle Datensaetze gleichzeitig neu verschluesselt werden muessen.

CREATE TABLE credential (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text        NOT NULL UNIQUE,
    type         text        NOT NULL CHECK (type IN (
                                 'PASSWORD', 'API_TOKEN', 'SSH_PRIVATE_KEY',
                                 'S3_KEYPAIR', 'RESTIC_REPOSITORY_PASSWORD', 'PUSHOVER')),
    description  text,
    wrapped_dek  bytea       NOT NULL,
    dek_iv       bytea       NOT NULL,
    ciphertext   bytea       NOT NULL,
    payload_iv   bytea       NOT NULL,
    key_version  integer     NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------ Quellen

CREATE TABLE backup_source (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                text        NOT NULL UNIQUE,
    type                text        NOT NULL CHECK (type IN (
                                        'LOCAL_PATH', 'POSTGRES', 'GITHUB',
                                        'S3', 'SFTP', 'FTP', 'BLOCK_DEVICE')),
    description         text,
    config              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    last_check_at       timestamptz,
    last_check_ok       boolean,
    last_check_message  text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- Mehrere Zugangsdaten je Quelle sind moeglich (etwa SSH-Key und Passphrase),
-- unterschieden ueber den Verwendungszweck.
CREATE TABLE source_credential (
    source_id     uuid NOT NULL REFERENCES backup_source (id) ON DELETE CASCADE,
    purpose       text NOT NULL,
    credential_id uuid NOT NULL REFERENCES credential (id) ON DELETE RESTRICT,
    PRIMARY KEY (source_id, purpose)
);

-- -------------------------------------------------------------------- Ziele
--
-- mode bestimmt die Storage-Engine: RESTIC liefert Snapshots, Deduplizierung,
-- Verschluesselung und Retention; MIRROR erzeugt eine unverschluesselte, nicht
-- versionierte 1:1-Kopie per rsync/rclone. Snapshot-Browser, Restore-Historie
-- und Retention gibt es nur im RESTIC-Modus.

CREATE TABLE backup_target (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                text        NOT NULL UNIQUE,
    type                text        NOT NULL CHECK (type IN ('LOCAL_PATH', 'S3', 'SFTP')),
    mode                text        NOT NULL CHECK (mode IN ('RESTIC', 'MIRROR')),
    description         text,
    config              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled             boolean     NOT NULL DEFAULT true,
    last_check_at       timestamptz,
    last_check_ok       boolean,
    last_check_message  text,
    capacity_bytes      bigint,
    free_bytes          bigint,
    used_bytes          bigint,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    -- Ein restic-Repository braucht zwingend ein Passwort; ein Spiegelziel nie.
    CONSTRAINT target_mode_requires_restic_config CHECK (mode <> 'RESTIC' OR config ? 'repositoryPasswordCredentialId')
);

CREATE TABLE target_credential (
    target_id     uuid NOT NULL REFERENCES backup_target (id) ON DELETE CASCADE,
    purpose       text NOT NULL,
    credential_id uuid NOT NULL REFERENCES credential (id) ON DELETE RESTRICT,
    PRIMARY KEY (target_id, purpose)
);

-- --------------------------------------------------------------- Retention

CREATE TABLE retention_policy (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name             text        NOT NULL UNIQUE,
    keep_last        integer     CHECK (keep_last        IS NULL OR keep_last        >= 0),
    keep_hourly      integer     CHECK (keep_hourly      IS NULL OR keep_hourly      >= 0),
    keep_daily       integer     CHECK (keep_daily       IS NULL OR keep_daily       >= 0),
    keep_weekly      integer     CHECK (keep_weekly      IS NULL OR keep_weekly      >= 0),
    keep_monthly     integer     CHECK (keep_monthly     IS NULL OR keep_monthly     >= 0),
    keep_yearly      integer     CHECK (keep_yearly      IS NULL OR keep_yearly      >= 0),
    keep_within_days integer     CHECK (keep_within_days IS NULL OR keep_within_days >= 0),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    -- Eine Regel, die nichts behaelt, wuerde beim ersten Prune alles loeschen.
    CONSTRAINT retention_keeps_something CHECK (
        coalesce(keep_last, 0) + coalesce(keep_hourly, 0) + coalesce(keep_daily, 0)
      + coalesce(keep_weekly, 0) + coalesce(keep_monthly, 0) + coalesce(keep_yearly, 0)
      + coalesce(keep_within_days, 0) > 0)
);

-- -------------------------------------------------------------------- Plaene

CREATE TABLE backup_plan (
    id                        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                      text        NOT NULL UNIQUE,
    description               text,
    source_id                 uuid        NOT NULL REFERENCES backup_source (id) ON DELETE RESTRICT,
    retention_policy_id       uuid        REFERENCES retention_policy (id) ON DELETE SET NULL,
    cron_expression           text        NOT NULL,
    timezone                  text        NOT NULL DEFAULT 'Europe/Zurich',
    enabled                   boolean     NOT NULL DEFAULT true,
    timeout_minutes           integer     NOT NULL DEFAULT 360 CHECK (timeout_minutes > 0),
    max_retries               integer     NOT NULL DEFAULT 2   CHECK (max_retries >= 0),
    missed_run_policy         text        NOT NULL DEFAULT 'SKIP' CHECK (missed_run_policy IN ('SKIP', 'CATCH_UP')),
    notify_on                 text        NOT NULL DEFAULT 'FAILURE' CHECK (notify_on IN ('FAILURE', 'ALWAYS', 'NEVER')),
    -- Grundlage des internen Dead-Man-Switch: Ueberschreitet der Abstand zum letzten
    -- Lauf dieses Intervall deutlich, gilt der Plan als ueberfaellig.
    expected_interval_minutes integer     CHECK (expected_interval_minutes IS NULL OR expected_interval_minutes > 0),
    next_run_at               timestamptz,
    last_run_at               timestamptz,
    last_run_status           text,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);

-- Treffer fuer den Scheduler-Poller; Teilindex, weil deaktivierte Plaene nie faellig sind.
CREATE INDEX idx_backup_plan_due ON backup_plan (next_run_at) WHERE enabled;

-- Ein Plan schreibt auf mehrere Ziele (3-2-1-Regel).
CREATE TABLE plan_target (
    plan_id    uuid    NOT NULL REFERENCES backup_plan (id)   ON DELETE CASCADE,
    target_id  uuid    NOT NULL REFERENCES backup_target (id) ON DELETE RESTRICT,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (plan_id, target_id)
);

-- --------------------------------------------------------------------- Laeufe

CREATE TABLE backup_run (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id           uuid        NOT NULL REFERENCES backup_plan (id) ON DELETE CASCADE,
    trigger_type      text        NOT NULL CHECK (trigger_type IN ('SCHEDULE', 'MANUAL', 'RETRY', 'API')),
    status            text        NOT NULL CHECK (status IN (
                                      'QUEUED', 'RUNNING', 'SUCCESS', 'PARTIAL',
                                      'FAILED', 'CANCELLED', 'TIMEOUT')),
    attempt           integer     NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    queued_at         timestamptz NOT NULL DEFAULT now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    bytes_processed   bigint,
    bytes_transferred bigint,
    files_new         bigint,
    files_changed     bigint,
    files_unchanged   bigint,
    log_path          text,
    error_summary     text
);

CREATE INDEX idx_backup_run_plan_time ON backup_run (plan_id, queued_at DESC);
CREATE INDEX idx_backup_run_active    ON backup_run (status) WHERE status IN ('QUEUED', 'RUNNING');

-- Ein Lauf zerfaellt in Schritte. Erst dadurch ist PARTIAL darstellbar: Beschaffung
-- erfolgreich, Uebertragung auf Ziel A erfolgreich, auf Ziel B fehlgeschlagen.
CREATE TABLE run_step (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id            uuid        NOT NULL REFERENCES backup_run (id) ON DELETE CASCADE,
    seq               integer     NOT NULL,
    kind              text        NOT NULL CHECK (kind IN (
                                      'PREPARE', 'ACQUIRE', 'TRANSFER', 'VERIFY', 'PRUNE', 'CLEANUP')),
    target_id         uuid        REFERENCES backup_target (id) ON DELETE SET NULL,
    status            text        NOT NULL CHECK (status IN (
                                      'PENDING', 'RUNNING', 'SUCCESS', 'FAILED',
                                      'SKIPPED', 'CANCELLED', 'TIMEOUT')),
    -- Container-ID des Runners. Traegt das Wiederanhaengen nach einem Backend-Neustart:
    -- laufende Container werden ueber ihr Label gefunden und hier zugeordnet.
    container_id      text,
    image             text,
    command           text[],
    exit_code         integer,
    started_at        timestamptz,
    finished_at       timestamptz,
    bytes_transferred bigint,
    message           text,
    UNIQUE (run_id, seq)
);

CREATE INDEX idx_run_step_container ON run_step (container_id) WHERE container_id IS NOT NULL;

-- ------------------------------------------------------------------ Snapshots

CREATE TABLE snapshot (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id        uuid        REFERENCES backup_run (id)    ON DELETE SET NULL,
    plan_id       uuid        NOT NULL REFERENCES backup_plan (id)   ON DELETE CASCADE,
    target_id     uuid        NOT NULL REFERENCES backup_target (id) ON DELETE CASCADE,
    external_id   text        NOT NULL,
    short_id      text,
    size_bytes    bigint,
    snapshot_time timestamptz NOT NULL,
    retention_tag text        CHECK (retention_tag IS NULL OR retention_tag IN (
                                  'LAST', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    -- Von der Retention ausgenommen, etwa der Stand vor einer Migration.
    pinned        boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (target_id, external_id)
);

CREATE INDEX idx_snapshot_plan_time ON snapshot (plan_id, snapshot_time DESC);

-- ------------------------------------------------------------ Benachrichtigung

CREATE TABLE notification_channel (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text        NOT NULL UNIQUE,
    type         text        NOT NULL CHECK (type IN ('PUSHOVER', 'WEBHOOK', 'SMTP')),
    config       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled      boolean     NOT NULL DEFAULT true,
    min_severity text        NOT NULL DEFAULT 'WARNING' CHECK (min_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Outbox statt Direktversand: Eine Alarmmeldung, die selbst verloren geht,
-- ist schlimmer als keine.
CREATE TABLE notification_outbox (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id       uuid        NOT NULL REFERENCES notification_channel (id) ON DELETE CASCADE,
    event_type       text        NOT NULL,
    severity         text        NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    title            text        NOT NULL,
    body             text        NOT NULL,
    payload          jsonb,
    plan_id          uuid        REFERENCES backup_plan (id) ON DELETE CASCADE,
    run_id           uuid        REFERENCES backup_run (id)  ON DELETE CASCADE,
    status           text        NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                                     'PENDING', 'SENT', 'FAILED', 'ABANDONED')),
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_error       text,
    -- Pushover liefert bei Emergency-Prioritaet eine Quittung zurueck, ueber die
    -- sich abfragen laesst, ob die Meldung bestaetigt wurde.
    external_receipt text,
    acknowledged_at  timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    sent_at          timestamptz
);

CREATE INDEX idx_notification_outbox_due ON notification_outbox (next_attempt_at) WHERE status = 'PENDING';
