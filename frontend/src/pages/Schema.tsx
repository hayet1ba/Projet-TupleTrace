import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { createDataset } from '../api/services';
import { getDraft, setDraft } from '../lib/csv';

const fmt = (n?: number) => (n === undefined ? '' : String(Math.round(n * 1e4) / 1e4));

export default function Schema() {
  const draft = getDraft();
  const nav = useNavigate();
  const [marked, setMarked] = useState<Record<string, boolean>>({});
  const [tol, setTol] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  if (!draft || !draft.pk) return <Navigate to="/datasets/import" replace />;

  const isForbidden = (name: string, f: boolean) => f || name === draft.pk;

  async function save() {
    const cols = draft!.columns.filter((c) => marked[c.name]);
    if (!cols.length) return setError('Choisissez au moins une colonne à marquer.');
    const bad = cols.find((c) => !(Number(tol[c.name]) > 0));
    if (bad) return setError(`Indiquez une tolérance supérieure à 0 pour « ${bad.name} ».`);
    try {
      const d = await createDataset(draft!, draft!.pk!, cols.map((c) => ({ name: c.name, tolerance: Number(tol[c.name]) })));
      setDraft(null);
      nav(`/datasets/${d.id}`);
    } catch (e) { setError(errorMessage(e)); }
  }

  return (
    <section>
      <h1>Colonnes et tolérances</h1>
      <p>Clé stable : <strong>{draft.pk}</strong>. Seules les colonnes numériques avec une tolérance peuvent être marquées.</p>
      <div className="scroll">
        <table>
          <caption className="sr">Profil des colonnes</caption>
          <thead><tr><th>Colonne</th><th>Type</th><th>Min</th><th>Max</th><th>Moyenne</th><th>Marquer</th><th>Tolérance</th></tr></thead>
          <tbody>
            {draft.columns.map((c) => {
              const off = isForbidden(c.name, c.forbidden);
              const why = c.name === draft.pk ? 'Clé stable' : c.reason;
              return (
                <tr key={c.name} className={off ? 'muted' : ''}>
                  <th scope="row">{c.name}</th>
                  <td>{c.type === 'number' ? 'Numérique' : 'Texte'}</td>
                  <td>{fmt(c.min)}</td><td>{fmt(c.max)}</td><td>{fmt(c.mean)}</td>
                  <td>
                    <input type="checkbox" aria-label={`Marquer ${c.name}`} disabled={off} checked={!!marked[c.name]}
                      onChange={(e) => setMarked({ ...marked, [c.name]: e.target.checked })} />
                    {off && <small> {why}</small>}
                  </td>
                  <td>
                    <input aria-label={`Tolérance de ${c.name}`} inputMode="decimal" size={6} disabled={off || !marked[c.name]}
                      value={tol[c.name] ?? ''} onChange={(e) => setTol({ ...tol, [c.name]: e.target.value })} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <button onClick={() => void save()}>Enregistrer le jeu</button>
    </section>
  );
}
