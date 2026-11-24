import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { getDataset, protectDataset } from '../api/services';
import StepUpForm from '../components/StepUpForm';
import { useAuth } from '../auth/AuthContext';
import { Status } from '../components/State';
import { useAsync } from '../lib/useAsync';
import { DATASET_LABEL } from './Datasets';

export default function DatasetDetail() {
  const { id = '' } = useParams();
  const { data, error, loading, reload } = useAsync(() => getDataset(id));
  const [msg, setMsg] = useState<string | null>(null);
  const { isStrong } = useAuth();
  const [stepUp, setStepUp] = useState(false);

  async function protect() {
    setMsg(null);
    if (!isStrong()) return setStepUp(true);
    try { await protectDataset(id); reload(); } catch (e) { setMsg(errorMessage(e)); }
  }
  return (
    <section>
      <h1>{data?.name ?? 'Jeu de données'}</h1>
      <Status loading={loading} error={error} />
      {data && (
        <div className="card">
          <p>Version {data.version} · {data.rows} lignes · Statut : <strong>{DATASET_LABEL[data.status]}</strong></p>
          {data.status === 'IMPORTED' && (
            <>
              <p>La protection insère le filigrane et le sceau. Elle exige votre second facteur.</p>
              <button onClick={() => void protect()}>Protéger</button>
            </>
          )}
          {data.status === 'PROTECTED' && <p>Ce jeu est protégé. Vous pouvez préparer une distribution.</p>}
          {msg && <p className="error" role="alert">{msg}</p>}
        </div>
      )}
      {stepUp && <StepUpForm onDone={() => { setStepUp(false); void protect(); }} />}
    </section>
  );
}
