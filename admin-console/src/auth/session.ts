import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import type { RuntimeConfig } from '../config';

/**
 * OIDC authorization code + PKCE (S256). Tokens live in memory only: the user store is an
 * InMemoryWebStorage, so a reload signs in again (silently, if the IdP session is still valid).
 * Only the transient sign-in state (state + PKCE verifier, no tokens) uses sessionStorage, because
 * it must survive the redirect to the IdP and back.
 */
export function createUserManager(cfg: RuntimeConfig, origin: string): UserManager {
  return new UserManager({
    authority: cfg.oidc.authority,
    client_id: cfg.oidc.clientId,
    scope: cfg.oidc.scope,
    redirect_uri: `${origin}/callback`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    // Renewal uses the refresh token (no hidden iframes, so the CSP needs no frame-src).
    automaticSilentRenew: true,
    monitorSession: false,
    loadUserInfo: false,
    filterProtocolClaims: true,
  });
}

export interface ReturnState {
  returnTo: string;
}

export function returnTo(user: User | null | undefined): string {
  const s = user?.state as Partial<ReturnState> | undefined;
  const r = s?.returnTo;
  // Only same-origin paths: never redirect to a URL carried through the IdP.
  return typeof r === 'string' && r.startsWith('/') && !r.startsWith('//') ? r : '/';
}
