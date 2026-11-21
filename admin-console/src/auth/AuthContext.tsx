import type { User, UserManager } from 'oidc-client-ts';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { returnTo } from './session';

export interface Auth {
  user: User | null;
  /** The current access token (memory only). */
  token: () => string | undefined;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  /** Called on 401: drop the session and show the sign-in screen. */
  expire: () => void;
}

const Ctx = createContext<Auth | null>(null);

/**
 * Marks that this tab signed in (no token, just "1"), so a reload goes straight back through the
 * IdP's SSO session instead of showing the sign-in screen. Tokens themselves stay in memory.
 */
const SIGNED_IN = 'apigw.signedIn';

function flag(on?: boolean): boolean {
  try {
    if (on === true) {
      window.sessionStorage.setItem(SIGNED_IN, '1');
    } else if (on === false) {
      window.sessionStorage.removeItem(SIGNED_IN);
    }
    return window.sessionStorage.getItem(SIGNED_IN) === '1';
  } catch {
    return false;
  }
}

export function useAuth(): Auth {
  const a = useContext(Ctx);
  if (a === null) {
    throw new Error('useAuth outside AuthProvider');
  }
  return a;
}

type Phase = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error'; message: string };

export function AuthProvider(props: { manager: UserManager; children: ReactNode; signedOut: ReactNode }) {
  const { manager } = props;
  const [user, setUserState] = useState<User | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' });
  // The API client reads the token synchronously; the ref is updated together with the state.
  const userRef = useRef<User | null>(null);
  const setUser = useCallback((u: User | null) => {
    userRef.current = u;
    setUserState(u);
  }, []);

  useEffect(() => {
    const loaded = (u: User) => {
      setUser(u);
    };
    const unloaded = () => {
      setUser(null);
    };
    manager.events.addUserLoaded(loaded);
    manager.events.addUserUnloaded(unloaded);
    manager.events.addSilentRenewError(unloaded);
    let cancelled = false;
    const start = async () => {
      if (window.location.pathname === '/callback') {
        const u = await manager.signinRedirectCallback();
        if (!cancelled) {
          flag(true);
          setUser(u);
          window.history.replaceState(null, '', returnTo(u));
        }
        return true;
      }
      const u = await manager.getUser();
      if (u && !u.expired) {
        setUser(u);
        return true;
      }
      if (flag()) {
        // Reloaded after signing in: re-authenticate (usually without a prompt) and come back here.
        await manager.signinRedirect({ state: { returnTo: window.location.pathname + window.location.search } });
        return false;
      }
      return true;
    };
    start().then((done) => {
      if (!cancelled && done) {
        setPhase({ kind: 'ready' });
      }
    }, (e: unknown) => {
      if (!cancelled) {
        flag(false);
        // Never show IdP error details verbatim beyond their message.
        setPhase({ kind: 'error', message: e instanceof Error ? e.message : 'sign-in failed' });
        window.history.replaceState(null, '', '/');
      }
    });
    return () => {
      cancelled = true;
      manager.events.removeUserLoaded(loaded);
      manager.events.removeUserUnloaded(unloaded);
      manager.events.removeSilentRenewError(unloaded);
    };
  }, [manager, setUser]);

  const signIn = useCallback(async () => {
    const path = window.location.pathname === '/callback' ? '/' : window.location.pathname + window.location.search;
    await manager.signinRedirect({ state: { returnTo: path } });
  }, [manager]);

  const signOut = useCallback(async () => {
    const u = userRef.current;
    flag(false);
    await manager.removeUser();
    setUser(null);
    if (u?.id_token) {
      await manager.signoutRedirect({ id_token_hint: u.id_token });
    }
  }, [manager, setUser]);

  const expire = useCallback(() => {
    flag(false);
    void manager.removeUser();
    setUser(null);
  }, [manager, setUser]);

  const token = useCallback(() => userRef.current?.access_token, []);

  const value = useMemo<Auth>(() => ({ user, token, signIn, signOut, expire }), [user, token, signIn, signOut, expire]);

  if (phase.kind === 'loading') {
    return <p className="splash" role="status">Signing in…</p>;
  }
  return (
    <Ctx.Provider value={value}>
      {phase.kind === 'error' && <p className="alert alert-error" role="alert">Sign-in failed: {phase.message}</p>}
      {user ? props.children : props.signedOut}
    </Ctx.Provider>
  );
}
