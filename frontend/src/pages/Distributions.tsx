import { useState, type FormEvent } from 'react';
import { errorMessage } from '../api/client';
import { listDatasets, listDistributions, prepareDistribution, validateDistribution } from '../api/services';
import { useAuth } from '../auth/AuthContext';
import StepUpForm from '../components/StepUpForm';
import { Status } from '../components/State';
import { useAsync } from '../lib/useAsync';

const STATE = { PENDING: 'En attente de validation', VALIDATED: 'Validée', FAILED: 'Échec', REVOKED: 'Révoquée' } as const;

export default function Distributions() {
  const { user, isStrong } = useAuth();
  const [pending, setPending] = useState<string | null>(null);
  const ds = useAsync(listDatasets);
  const dist = useAsync(listDistributions);
  const [datasetId, setDatasetId] = useState('');
  const [raw, setRaw] = useState('');
  const [error, setError] = useState<string | null>(null);
  const protectedSets = ds.data?.filter((d) => d.status === 'PROTECTED') ?? [];
  const recipients = raw.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);

  async function prepare(e: FormEvent) {
    e.preventDefault();
    const id = datasetId || protectedSets[0]?.id;
    if (!id) return setError('Aucun jeu protégé disponible.');
    if (recipients.length === 0 || recipients.length > 30) return setError('Indiquez entre 1 et 30 destinataires.');
    if (recipients.some((r) => !r.includes('@'))) return setError('Chaque ligne doit être une adresse e-mail.');
    try { await prepareDistribution(id, recipients, user!.id); setRaw(''); setError(null); dist.reload(); }
    catch (err) { setError(errorMessage(err)); }
  }
  async function validate(id: string) {
    if (!isStrong()) return setPending(id);
    try { await validateDistribution(id); dist.reload(); } catch (err) { setError(errorMessage(err)); }
  }

  return (
    <section>
      <h1>Distributions</h1>
      <Status loading={ds.loading || dist.loading} error={ds.error ?? dist.error} />
      <form className="card" onSubmit={(e) => void prepare(e)}>
        <h2>Préparer une distribution</h2>
        <label>Jeu protégé
          <select value={datasetId} onChange={(e) => setDatasetId(e.target.value)}>
            {protectedSets.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
        <label>Destinataires (une adresse e-mail par ligne, 30 maximum)
          <textarea rows={4} value={raw} onChange={(e) => setRaw(e.target.value)} />
        </label>
        <p className="hint">Aperçu : {recipients.length} destinataire(s), une copie distincte pour chacun. Aucune copie n’est générée avant la validation par un autre propriétaire.</p>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit">Préparer</button>
      </form>
      {pending && <StepUpForm onDone={() => { const id = pending; setPending(null); void validate(id); }} />}
      {dist.data && dist.data.length > 0 && (
        <table>
          <thead><tr><th>Jeu</th><th>Destinataires</th><th>État</th><th>Action</th></tr></thead>
          <tbody>{dist.data.map((d) => {
            const self = d.preparedBy === user?.id;
            return (
              <tr key={d.id}>
                <th scope="row">{d.datasetName}</th><td>{d.recipients.length}</td><td><span className={`badge b-${d.state}`}>{STATE[d.state]}</span></td>
                <td>
                  {d.state === 'PENDING' && user?.roles.includes('owner') && (
                    <button disabled={self} onClick={() => void validate(d.id)}>Valider</button>
                  )}
                  {d.state === 'PENDING' && self && <small> Vous l’avez préparée : un autre propriétaire doit la valider.</small>}
                </td>
              </tr>
            );
          })}</tbody>
        </table>
      )}
    </section>
  );
}
