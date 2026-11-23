import { useState, type ChangeEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { readDraft, setDraft, type CsvDraft } from '../lib/csv';

export default function ImportCsv() {
  const nav = useNavigate();
  const [errors, setErrors] = useState<string[]>([]);
  const [draft, setLocal] = useState<CsvDraft | null>(null);
  const [pk, setPk] = useState('');

  async function onFile(e: ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    setErrors([]); setLocal(null);
    if (!f) return;
    const r = await readDraft(f);
    setErrors(r.errors);
    if (r.draft) { setLocal(r.draft); setPk(r.draft.pkCandidates[0]); }
  }

  return (
    <section>
      <h1>Importer un CSV</h1>
      <p>Fichier .csv en UTF-8, 50 Mo maximum. Ces contrôles sont un confort : le serveur les refait.</p>
      <label>Fichier CSV
        <input type="file" accept=".csv,text/csv" onChange={(e) => void onFile(e)} />
      </label>
      {errors.length > 0 && <ul className="error" role="alert">{errors.map((m) => <li key={m}>{m}</li>)}</ul>}
      {draft && (
        <div className="card">
          <p>{draft.columns.length} colonnes détectées, séparateur « {draft.delimiter === '\t' ? 'tabulation' : draft.delimiter} », {draft.sampleRows} lignes analysées dans l’aperçu.</p>
          <label>Colonne de clé stable
            <select value={pk} onChange={(e) => setPk(e.target.value)}>
              {draft.pkCandidates.map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <button onClick={() => { setDraft({ ...draft, pk }); nav('/datasets/import/schema'); }}>Continuer vers les colonnes</button>
        </div>
      )}
    </section>
  );
}
