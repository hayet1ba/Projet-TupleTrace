import type { InvestigationResult, Verdict } from '../api/types';

export const VERDICTS: Record<Verdict, { label: string; cls: string; icon: string; meaning: string }> = {
  MARK_RECOGNIZED: { label: 'Marque reconnue', cls: 'ok', icon: '✔', meaning: 'La marque du propriétaire a été retrouvée dans l’extrait.' },
  COPY_IDENTIFIED: { label: 'Copie identifiée', cls: 'ok', icon: '✔', meaning: 'L’empreinte correspond à une copie distribuée. C’est un indice technique, pas une preuve de responsabilité.' },
  INDETERMINATE: { label: 'Indéterminé', cls: 'warn', icon: '?', meaning: 'Les données sont insuffisantes ou dégradées : aucune conclusion n’est tirée.' },
  NO_MARK: { label: 'Aucune marque détectée', cls: 'neutral', icon: '–', meaning: 'Aucune marque du propriétaire n’a été trouvée dans l’extrait.' },
};

export default function VerdictCard({ r }: { r: InvestigationResult }) {
  const v = VERDICTS[r.verdict];
  return (
    <section className={`card verdict ${v.cls}`} aria-live="polite">
      <h2><span aria-hidden="true">{v.icon} </span>{v.label}</h2>
      <p>{v.meaning}</p>
      <p>{r.reason}</p>
      {r.n !== undefined && (
        <dl>
          <dt>Cellules testées (n)</dt><dd>{r.n}</dd>
          <dt>Concordances (k)</dt><dd>{r.k}</dd>
          <dt>p-valeur</dt><dd>{r.pValue}</dd>
          <dt>Seuil corrigé</dt><dd>{r.threshold}</dd>
        </dl>
      )}
    </section>
  );
}
