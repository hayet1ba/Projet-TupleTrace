import { Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import RequireRole from './components/RequireRole';
import Account from './pages/Account';
import Admin from './pages/Admin';
import Audit from './pages/Audit';
import Dashboard from './pages/Dashboard';
import DatasetDetail from './pages/DatasetDetail';
import Datasets from './pages/Datasets';
import Distributions from './pages/Distributions';
import ImportCsv from './pages/ImportCsv';
import Investigations from './pages/Investigations';
import Login from './pages/Login';
import MyCopy from './pages/MyCopy';
import Placeholder from './pages/Placeholder';
import Schema from './pages/Schema';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireRole />}>
        <Route element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route element={<RequireRole roles={['owner']} />}>
            <Route path="datasets" element={<Datasets />} />
            <Route path="datasets/import" element={<ImportCsv />} />
            <Route path="datasets/import/schema" element={<Schema />} />
            <Route path="datasets/:id" element={<DatasetDetail />} />
          </Route>
          <Route element={<RequireRole roles={['owner', 'manager']} />}>
            <Route path="distributions" element={<Distributions />} />
          </Route>
          <Route element={<RequireRole roles={['recipient']} />}><Route path="copies" element={<MyCopy />} /></Route>
          <Route element={<RequireRole roles={['owner', 'investigator']} />}><Route path="investigations" element={<Investigations />} /></Route>
          <Route element={<RequireRole roles={['admin']} />}><Route path="admin" element={<Admin />} /></Route>
          <Route element={<RequireRole roles={['auditor']} />}><Route path="audit" element={<Audit />} /></Route>
          <Route path="account" element={<Account />} />
          <Route path="*" element={<Placeholder title="Page introuvable" bf="404" />} />
        </Route>
      </Route>
    </Routes>
  );
}
