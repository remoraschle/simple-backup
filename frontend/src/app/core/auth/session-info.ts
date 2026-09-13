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

/**
 * Welche Anmeldewege es gibt.
 *
 * <p>Kommt vom Backend, weil nur dort steht, ob ein Anbieter eingerichtet ist. Ein Knopf,
 * der ins Leere führt, wäre schlimmer als keiner.
 */
export interface LoginProviders {
  readonly oidcEnabled: boolean;
  readonly displayName: string | null;
  readonly authorizationUrl: string | null;
}

export const PASSWORD_ONLY: LoginProviders = {
  oidcEnabled: false,
  displayName: null,
  authorizationUrl: null,
};
