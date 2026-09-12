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
