import { Route, Routes } from 'react-router';
import { Layout } from './components/Layout';
import { AuditPage } from './pages/AuditPage';
import { ChangeSetPage, ChangeSetsPage } from './pages/ChangeSets';
import { GatewaysPage } from './pages/GatewaysPage';
import { HistoryEntryPage, HistoryPage } from './pages/History';
import { NotFound } from './pages/NotFound';
import { ObjectPage } from './pages/ObjectPage';
import { ObjectsPage } from './pages/ObjectsPage';
import { PluginsPage } from './pages/PluginsPage';
import { SettingsPage } from './pages/SettingsPage';

export function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<GatewaysPage />} />
        <Route path="/objects/:slug" element={<ObjectsPage />} />
        <Route path="/objects/:slug/new" element={<ObjectPage />} />
        <Route path="/objects/:slug/:id" element={<ObjectPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/plugins" element={<PluginsPage />} />
        <Route path="/changesets" element={<ChangeSetsPage />} />
        <Route path="/changesets/:id" element={<ChangeSetPage />} />
        <Route path="/history" element={<HistoryPage />} />
        <Route path="/history/:seq" element={<HistoryEntryPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </Layout>
  );
}
