import { listAccounts } from '../api/services';
import { ROLE_LABELS } from '../auth/roles';
import { Status } from '../components/State';
import { useAsync } from '../lib/useAsync';

export default function Admin() {
  const { data, error, loading } = useAsync(listAccounts);
  return (
    <section>
      <h1>Administration</h1>
      <p>Comptes et organisations. Vous n’avez accès ni aux jeux de données ni aux clés.</p>
      <Status loading={loading} error={error} />
      {data && (
        <table>
          <thead><tr><th>E-mail</th><th>Rôle</th><th>Statut</th></tr></thead>
          <tbody>{data.map((a) => <tr key={a.id}><th scope="row">{a.email}</th><td>{ROLE_LABELS[a.role]}</td><td>{a.active ? 'Actif' : 'Suspendu'}</td></tr>)}</tbody>
        </table>
      )}
    </section>
  );
}
