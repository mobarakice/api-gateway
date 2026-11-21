import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode, useMemo } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';
import { AdminClient } from './api/client';
import { ClientContext } from './api/hooks';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { createUserManager } from './auth/session';
import { ActiveChangeSetProvider } from './components/ActiveChangeSet';
import { loadConfig, type RuntimeConfig } from './config';
import { SignIn } from './pages/SignIn';
import './styles.css';

function WithClient({ cfg }: { cfg: RuntimeConfig }) {
  const auth = useAuth();
  const client = useMemo(() => new AdminClient(cfg.apiBase, auth.token, auth.expire), [cfg.apiBase, auth.token, auth.expire]);
  const queries = useMemo(() => new QueryClient({
    defaultOptions: {
      queries: {
        retry: (n, e) => n < 2 && !(e instanceof Error && 'status' in e && [401, 403, 404].includes((e as { status: number }).status)),
        refetchOnWindowFocus: true,
      },
    },
  }), []);
  return (
    <ClientContext.Provider value={client}>
      <QueryClientProvider client={queries}>
        <ActiveChangeSetProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </ActiveChangeSetProvider>
      </QueryClientProvider>
    </ClientContext.Provider>
  );
}

const root = createRoot(document.getElementById('root') as HTMLElement);

loadConfig().then((cfg) => {
  const manager = createUserManager(cfg, window.location.origin);
  root.render(
    <StrictMode>
      <AuthProvider manager={manager} signedOut={<SignIn />}>
        <WithClient cfg={cfg} />
      </AuthProvider>
    </StrictMode>,
  );
}, (e: unknown) => {
  root.render(
    <main className="splash">
      <h1>Console unavailable</h1>
      <p role="alert">{e instanceof Error ? e.message : 'configuration could not be loaded'}</p>
    </main>,
  );
});
