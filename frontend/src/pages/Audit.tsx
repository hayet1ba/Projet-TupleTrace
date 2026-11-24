import { listAudit } from '../api/services';
import { Status } from '../components/State';
import { useAsync } from '../lib/useAsync';

export default function Audit() {
  const { data, error, loading } = useAsync(listAudit);
  return (
    <section>
      <h1>Journal d’audit</h1>
      <p>Lecture seule. Le journal est chaîné : l’état de la chaîne est calculé par le serveur.</p>
      <Status loading={loading} error={error} />
      {data && (
        <table>
          <thead><tr><th>Date</th><th>Acteur</th><th>Action</th></tr></thead>
          <tbody>{data.map((e) => <tr key={e.id}><th scope="row">{new Date(e.at).toLocaleString('fr-FR')}</th><td>{e.actor}</td><td>{e.action}</td></tr>)}</tbody>
        </table>
      )}
    </section>
  );
}
