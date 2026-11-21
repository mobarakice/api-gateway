import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';

export function SignIn() {
  const auth = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();
  return (
    <main className="splash" id="main">
      <h1>API Gateway console</h1>
      <p>Sign in with your organisation account to manage gateway configuration.</p>
      {error && <p className="alert alert-error" role="alert">{error}</p>}
      <button type="button" className="btn btn-primary" disabled={busy} onClick={() => {
        setBusy(true);
        auth.signIn().catch((e: unknown) => {
          setBusy(false);
          setError(e instanceof Error ? `Could not reach the identity provider: ${e.message}` : 'Sign-in failed');
        });
      }}>{busy ? 'Redirecting…' : 'Sign in'}</button>
    </main>
  );
}
