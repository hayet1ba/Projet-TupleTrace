export function Status({ loading, error }: { loading: boolean; error: string | null }) {
  if (error) return <p className="error" role="alert">{error}</p>;
  if (loading) return <p role="status">Chargement…</p>;
  return null;
}
