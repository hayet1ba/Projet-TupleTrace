import { Link } from 'react-router-dom';
import { listDatasets } from '../api/services';
import type { DatasetStatus } from '../api/types';
import { Status } from '../components/State';
import { useAsync } from '../lib/useAsync';

export const DATASET_LABEL: Record<DatasetStatus, string> = { IMPORTED: 'Importé', PROTECTED: 'Protégé', FAILED: 'Échec' };

export default function Datasets() {
  const { data, error, loading } = useAsync(listDatasets);
  return (
    <section>
      <h1>Jeux de données</h1>
      <Status loading={loading} error={error} />
      {data && data.length === 0 && <p>Aucun jeu pour l’instant. <Link to="/datasets/import">Importez un CSV</Link>.</p>}
      {data && data.length > 0 && (
        <table>
          <thead><tr><th>Nom</th><th>Version</th><th>Lignes</th><th>Statut</th></tr></thead>
          <tbody>{data.map((d) => (
            <tr key={d.id}><th scope="row"><Link to={`/datasets/${d.id}`}>{d.name}</Link></th><td>{d.version}</td><td>{d.rows}</td><td><span className={`badge b-${d.status}`}>{DATASET_LABEL[d.status]}</span></td></tr>
          ))}</tbody>
        </table>
      )}
    </section>
  );
}
