export type UserRole = 'ADMIN' | 'VIEWER';

/** Entspricht dem Record SessionInfo im Backend. */
export interface SessionInfo {
  readonly authenticated: boolean;
  readonly username: string | null;
  readonly role: UserRole | null;
  readonly mustChangePassword: boolean;
}

export const ANONYMOUS: SessionInfo = {
  authenticated: false,
  username: null,
  role: null,
  mustChangePassword: false,
};
