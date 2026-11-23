import { useState, type ChangeEvent } from 'react';
import { errorMessage } from '../api/client';
import { downloadReport, submitInvestigation } from '../api/services';
import type { InvestigationResult } from '../api/types';
import VerdictCard from '../components/Verdict';
import { checkFile } from '../lib/csv';
import { saveBlob } from '../lib/download';
import { USE_MOCKS } from '../mocks/data';

export default function Investigations() {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<InvestigationResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function pick(e: ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0] ?? null;
    const bad = f ? checkFile(f) : null;
    setError(bad); setFile(bad ? null : f); setResult(null);
  }
  async function run() {
    if (!file) return;
    setBusy(true); setError(null);
    try { setResult(await submitInvestigation(file)); } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }
  return (
    <section>
      <h1>Investigations</h1>
      <p>Déposez l’extrait suspect. Le serveur recalcule tous les résultats : le client n’envoie que le fichier.</p>
      <label>Extrait suspect (CSV)
        <input type="file" accept=".csv,text/csv" onChange={pick} />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <button disabled={!file || busy} onClick={() => void run()}>{busy ? 'Analyse…' : 'Lancer l’investigation'}</button>
      {result && <VerdictCard r={result} />}
      {result && (USE_MOCKS
        ? <p className="hint">Le rapport signé n’existe pas en mode simulé.</p>
        : <button onClick={() => void downloadReport(result.id).then((b) => saveBlob(b, 'rapport-investigation.pdf'))}>Télécharger le rapport signé</button>)}
    </section>
  );
}
