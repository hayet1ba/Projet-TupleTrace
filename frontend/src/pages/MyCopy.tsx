import { useState } from 'react';
import { errorMessage } from '../api/client';
import { downloadCopy, getMyCopy } from '../api/services';
import { Status } from '../components/State';
import { saveBlob } from '../lib/download';
import { useAsync } from '../lib/useAsync';

export default function MyCopy() {
  const { data, error, loading } = useAsync(getMyCopy);
  const [msg, setMsg] = useState<string | null>(null);
  async function download() {
    try { saveBlob(await downloadCopy(data!.id), `${data!.datasetName}`); setMsg(null); }
    catch (e) { setMsg(errorMessage(e)); }
  }
  return (
    <section>
      <h1>Ma copie</h1>
      <Status loading={loading} error={error} />
      {!loading && !error && !data && <p>Aucune copie ne vous a été attribuée.</p>}
      {data && (
        <div className="card">
          <p>{data.datasetName}</p>
          {data.state === 'VALIDATED'
            ? <button onClick={() => void download()}>Télécharger ma copie</button>
            : <p>Cette copie n’est plus disponible (accès révoqué ou non validé).</p>}
          {msg && <p className="error" role="alert">{msg}</p>}
        </div>
      )}
    </section>
  );
}
