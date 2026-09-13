/**
 * Die Typen der Backend-API.
 *
 * <p>Von Hand gepflegt, solange die API klein ist. Sobald sie wächst, sollten sie aus der
 * OpenAPI-Beschreibung erzeugt werden, die springdoc bereits ausliefert — dann fällt ein
 * Bruch beim Build auf statt im Browser.
 */

export type SourceType =
  'LOCAL_PATH' | 'POSTGRES' | 'GITHUB' | 'S3' | 'SFTP' | 'FTP' | 'BLOCK_DEVICE';

export type TargetType = 'LOCAL_PATH' | 'S3' | 'SFTP';

/** RESTIC versioniert und verschlüsselt, MIRROR erzeugt eine lesbare 1:1-Kopie. */
export type TargetMode = 'RESTIC' | 'MIRROR';

export type RunStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  /** Mindestens ein Ziel erfolgreich, mindestens eines gescheitert. */
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMEOUT';

export type StepStatus =
  'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'CANCELLED' | 'TIMEOUT';

export type StepKind = 'PREPARE' | 'ACQUIRE' | 'TRANSFER' | 'VERIFY' | 'PRUNE' | 'CLEANUP';

export type MissedRunPolicy = 'SKIP' | 'CATCH_UP';
export type NotifyOn = 'FAILURE' | 'ALWAYS' | 'NEVER';

export interface CheckResult {
  readonly at: string;
  readonly successful: boolean;
  readonly message: string | null;
}

export interface Capacity {
  readonly totalBytes: number | null;
  readonly freeBytes: number | null;
  readonly usedBytes: number | null;
}

export interface LocalPathSourceConfig {
  readonly type: 'LOCAL_PATH';
  readonly paths: string[];
  readonly excludes: string[];
  readonly oneFileSystem: boolean;
}

export type SourceConfig = LocalPathSourceConfig;

export interface LocalPathTargetConfig {
  readonly type: 'LOCAL_PATH';
  readonly path: string;
  readonly repositoryPasswordCredentialId: string | null;
}

export interface S3TargetConfig {
  readonly type: 'S3';
  readonly endpoint: string;
  readonly bucket: string;
  readonly prefix: string | null;
  readonly credentialId: string;
  readonly repositoryPasswordCredentialId: string | null;
}

export type TargetConfig = LocalPathTargetConfig | S3TargetConfig;

export interface Source {
  readonly id: string;
  readonly name: string;
  readonly type: SourceType;
  readonly description: string | null;
  readonly config: SourceConfig;
  readonly lastCheck: CheckResult | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface Target {
  readonly id: string;
  readonly name: string;
  readonly type: TargetType;
  readonly mode: TargetMode;
  readonly description: string | null;
  readonly config: TargetConfig;
  readonly enabled: boolean;
  readonly lastCheck: CheckResult | null;
  readonly capacity: Capacity | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface TargetReference {
  readonly id: string;
  readonly name: string;
  readonly mode: TargetMode;
}

export interface Plan {
  readonly id: string;
  readonly name: string;
  readonly description: string | null;
  readonly sourceId: string;
  readonly sourceName: string;
  readonly targets: TargetReference[];
  readonly retentionPolicyId: string | null;
  readonly cronExpression: string;
  readonly timezone: string;
  readonly enabled: boolean;
  readonly timeoutMinutes: number;
  readonly maxRetries: number;
  readonly missedRunPolicy: MissedRunPolicy;
  readonly notifyOn: NotifyOn;
  readonly expectedIntervalMinutes: number | null;
  readonly nextRunAt: string | null;
  readonly lastRunAt: string | null;
  readonly lastRunStatus: string | null;
  readonly createdAt: string;
}

export interface Run {
  readonly id: string;
  readonly planId: string;
  readonly planName: string | null;
  readonly status: RunStatus;
  readonly trigger: string;
  readonly queuedAt: string;
  readonly startedAt: string | null;
  readonly finishedAt: string | null;
  readonly durationSeconds: number | null;
  readonly bytesProcessed: number | null;
  /** Nach Deduplizierung tatsächlich übertragen — die aussagekräftige Zahl. */
  readonly bytesTransferred: number | null;
  readonly filesNew: number | null;
  readonly filesChanged: number | null;
  readonly errorSummary: string | null;
}

export interface RunStep {
  readonly id: string;
  readonly seq: number;
  readonly kind: StepKind;
  readonly targetId: string | null;
  readonly status: StepStatus;
  readonly exitCode: number | null;
  readonly startedAt: string | null;
  readonly finishedAt: string | null;
  readonly message: string | null;
  readonly command: string[];
}

export interface RunDetail {
  readonly summary: Run;
  readonly steps: RunStep[];
}

/**
 * Grossvater-Vater-Sohn-Regel.
 *
 * <p>Ein leeres Feld heisst „diese Stufe zählt nicht". Eine Regel, die nichts behält, lehnt
 * das Backend ab — sie würde beim ersten Aufräumen alles löschen.
 */
export interface RetentionRule {
  readonly keepLast: number | null;
  readonly keepHourly: number | null;
  readonly keepDaily: number | null;
  readonly keepWeekly: number | null;
  readonly keepMonthly: number | null;
  readonly keepYearly: number | null;
  readonly keepWithinDays: number | null;
}

export interface RetentionPolicy {
  readonly id: string;
  readonly name: string;
  readonly rule: RetentionRule;
}

export type ChannelType = 'PUSHOVER' | 'WEBHOOK';

export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export interface PushoverChannelConfig {
  readonly type: 'PUSHOVER';
  readonly credentialId: string;
  readonly device: string | null;
  /** Verlangt bei kritischen Meldungen eine Quittung — Pushover wiederholt bis dahin. */
  readonly emergency: boolean;
  readonly retrySeconds: number | null;
  readonly expireSeconds: number | null;
}

export interface WebhookChannelConfig {
  readonly type: 'WEBHOOK';
  readonly url: string;
  readonly headerName: string | null;
  readonly credentialId: string | null;
}

export type ChannelConfig = PushoverChannelConfig | WebhookChannelConfig;

export interface NotificationChannel {
  readonly id: string;
  readonly name: string;
  readonly type: ChannelType;
  readonly config: ChannelConfig;
  readonly enabled: boolean;
  readonly minSeverity: Severity;
  readonly createdAt: string;
}

export type OutboxStatus = 'PENDING' | 'SENT' | 'FAILED' | 'ABANDONED';

/** Eine Meldung auf dem Weg zu einem Kanal. */
export interface OutboxEntry {
  readonly id: string;
  readonly channelId: string;
  readonly eventType: string;
  readonly severity: Severity;
  readonly title: string;
  readonly body: string;
  readonly status: OutboxStatus;
  readonly attempts: number;
  readonly lastError: string | null;
  readonly planId: string | null;
  readonly runId: string | null;
  readonly createdAt: string;
  readonly sentAt: string | null;
}

export interface Snapshot {
  readonly id: string;
  readonly runId: string | null;
  readonly planId: string;
  readonly targetId: string;
  readonly externalId: string;
  readonly shortId: string;
  readonly sizeBytes: number | null;
  readonly snapshotTime: string;
  /** Von der Aufbewahrung ausgenommen, etwa der Stand vor einer Migration. */
  readonly pinned: boolean;
}

export interface SnapshotEntry {
  readonly path: string;
  readonly name: string;
  readonly directory: boolean;
  readonly sizeBytes: number | null;
  readonly modifiedAt: string | null;
}

export interface BrowseResult {
  readonly snapshotId: string;
  readonly path: string;
  readonly entries: SnapshotEntry[];
}

export type RestoreState = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface Restore {
  readonly id: string;
  readonly snapshotId: string;
  readonly targetPath: string;
  readonly includes: string[];
  readonly state: RestoreState;
  readonly message: string | null;
  readonly startedAt: string;
  readonly finishedAt: string | null;
}

export interface CheckOutcome {
  readonly targetId: string;
  readonly targetName: string;
  readonly successful: boolean;
  readonly message: string;
  readonly checkedAt: string;
}

/** Ergebnis der Stichprobe: eine echte Datei zurückgeholt und verglichen. */
export interface RestoreTestOutcome extends CheckOutcome {
  readonly path: string | null;
}

export interface Page<T> {
  readonly content: T[];
  readonly totalElements: number;
  readonly totalPages: number;
  readonly number: number;
  readonly size: number;
}

/** Ereignisse aus dem Live-Datenstrom eines laufenden Backups. */
export interface LogEvent {
  readonly line: string;
}

export interface ProgressEvent {
  readonly stepId: string;
  readonly percent: number;
  readonly filesDone: number | null;
  readonly totalFiles: number | null;
  readonly bytesDone: number | null;
  readonly totalBytes: number | null;
  readonly secondsRemaining: number | null;
}

export interface StepEvent {
  readonly stepId: string;
  readonly kind: StepKind | null;
  readonly status: StepStatus;
  readonly description: string | null;
}

export interface FinishedEvent {
  readonly status: RunStatus;
  readonly errorSummary: string | null;
}
