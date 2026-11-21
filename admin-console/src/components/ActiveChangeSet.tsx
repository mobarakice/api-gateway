import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

/**
 * The draft that edits are staged into. Kept in memory and sessionStorage (an ID, not a secret)
 * so a reload — which signs in again — keeps the editor's place.
 */
interface Active {
  id: string | undefined;
  set: (id: string | undefined) => void;
}

const KEY = 'apigw.activeChangeSet';
const Ctx = createContext<Active | null>(null);

function initial(): string | undefined {
  try {
    return window.sessionStorage.getItem(KEY) ?? undefined;
  } catch {
    return undefined;
  }
}

export function ActiveChangeSetProvider({ children }: { children: ReactNode }) {
  const [id, setId] = useState<string | undefined>(initial);
  const set = useCallback((next: string | undefined) => {
    setId(next);
    try {
      if (next === undefined) {
        window.sessionStorage.removeItem(KEY);
      } else {
        window.sessionStorage.setItem(KEY, next);
      }
    } catch {
      // Storage unavailable (private mode): memory only.
    }
  }, []);
  const v = useMemo(() => ({ id, set }), [id, set]);
  return <Ctx.Provider value={v}>{children}</Ctx.Provider>;
}

export function useActiveChangeSet(): Active {
  const a = useContext(Ctx);
  if (a === null) {
    throw new Error('useActiveChangeSet outside its provider');
  }
  return a;
}
