import { useState, type SyntheticEvent, type ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router';
import { useChangeSets, useCreateChangeSet, useMe } from '../api/hooks';
import { useAuth } from '../auth/AuthContext';
import { KINDS } from '../kinds';
import { can } from '../roles';
import { useActiveChangeSet } from './ActiveChangeSet';
import { Dialog, ProblemAlert } from './ui';

export function NewChangeSetDialog(p: { open: boolean; onClose: () => void }) {
  const [title, setTitle] = useState('');
  const create = useCreateChangeSet();
  const active = useActiveChangeSet();
  const navigate = useNavigate();
  const submit = (e: SyntheticEvent) => {
    e.preventDefault();
    create.mutate(title.trim(), {
      onSuccess: (cs) => {
        active.set(cs.id);
        setTitle('');
        p.onClose();
        void navigate(`/changesets/${cs.id}`);
      },
    });
  };
  return (
    <Dialog open={p.open} title="New change set" onClose={p.onClose}>
      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="cs-title">Title</label>
          <p className="hint" id="cs-title-hint">Say what the change is for; it is kept in history and audit.</p>
          <input id="cs-title" aria-describedby="cs-title-hint" required maxLength={200} value={title}
            onChange={(e) => { setTitle(e.target.value); }} />
        </div>
        <ProblemAlert error={create.error} />
        <div className="actions">
          <button type="submit" className="btn btn-primary" disabled={create.isPending || title.trim() === ''}>
            Create
          </button>
          <button type="button" className="btn" onClick={p.onClose}>Cancel</button>
        </div>
      </form>
    </Dialog>
  );
}

function ChangeSetPicker() {
  const me = useMe();
  const sets = useChangeSets();
  const active = useActiveChangeSet();
  const [open, setOpen] = useState(false);
  const known = sets.data?.some((s) => s.id === active.id) ?? true;
  return (
    <div className="cs-picker">
      <label htmlFor="active-cs">Change set</label>
      <select id="active-cs" value={known ? (active.id ?? '') : ''}
        onChange={(e) => { active.set(e.target.value === '' ? undefined : e.target.value); }}>
        <option value="">(none selected)</option>
        {(sets.data ?? []).map((s) => <option key={s.id} value={s.id}>{s.title} ({s.ops})</option>)}
      </select>
      {can(me.data?.role, 'editor') && (
        <button type="button" className="btn btn-small" onClick={() => { setOpen(true); }}>New change set</button>
      )}
      <NewChangeSetDialog open={open} onClose={() => { setOpen(false); }} />
    </div>
  );
}

export function Layout({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const me = useMe();
  const role = me.data?.role;
  const link = ({ isActive }: { isActive: boolean }) => (isActive ? 'nav-link active' : 'nav-link');
  return (
    <div className="app">
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="topbar">
        <span className="brand">API Gateway</span>
        <ChangeSetPicker />
        <div className="who">
          {me.data && <span>{me.data.subject} <span className="badge">{me.data.role}</span></span>}
          <button type="button" className="btn btn-small" onClick={() => { void auth.signOut(); }}>Sign out</button>
        </div>
      </header>
      <div className="body">
        <nav aria-label="Main" className="sidenav">
          <ul>
            <li><NavLink className={link} to="/" end>Gateways</NavLink></li>
            {KINDS.map((k) => <li key={k.slug}><NavLink className={link} to={`/objects/${k.slug}`}>{k.label}</NavLink></li>)}
            <li><NavLink className={link} to="/settings">Settings</NavLink></li>
            <li><NavLink className={link} to="/plugins">Plugin catalog</NavLink></li>
            <li><NavLink className={link} to="/changesets">Change sets</NavLink></li>
            <li><NavLink className={link} to="/history">History</NavLink></li>
            {can(role, 'admin') && <li><NavLink className={link} to="/audit">Audit</NavLink></li>}
          </ul>
        </nav>
        <main id="main" tabIndex={-1}>
          {me.isError ? <ProblemAlert error={me.error} /> : children}
        </main>
      </div>
    </div>
  );
}
