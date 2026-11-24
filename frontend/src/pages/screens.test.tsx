import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { prepareDistribution } from '../api/services';
import VerdictCard, { VERDICTS } from '../components/Verdict';
import { analyseCsv, setDraft } from '../lib/csv';
import { renderAs, axe } from '../test/utils';
import Admin from './Admin';
import Audit from './Audit';
import DatasetDetail from './DatasetDetail';
import Datasets from './Datasets';
import Distributions from './Distributions';
import ImportCsv from './ImportCsv';
import Investigations from './Investigations';
import MyCopy from './MyCopy';
import Schema from './Schema';
import { Route, Routes } from 'react-router-dom';

describe('écrans', () => {
  it('import : champ fichier accessible', async () => {
    const { container } = renderAs('owner', <ImportCsv />);
    expect(screen.getByLabelText(/fichier csv/i)).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('schéma : colonnes interdites grisées, tolérance exigée', async () => {
    const { draft } = analyseCsv('record_id,sensor,temperature\n1,S1,21.4\n2,S2,22.5\n', new File(['x'], 'a.csv'));
    setDraft({ ...draft!, pk: 'record_id' });
    const { container } = renderAs('owner', <Schema />, '/datasets/import/schema');
    expect(screen.getByLabelText('Marquer record_id')).toBeDisabled();
    expect(screen.getByLabelText('Marquer sensor')).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: /enregistrer/i }));
    expect(screen.getByRole('alert')).toHaveTextContent(/au moins une colonne/i);
    await userEvent.click(screen.getByLabelText('Marquer temperature'));
    await userEvent.click(screen.getByRole('button', { name: /enregistrer/i }));
    expect(screen.getByRole('alert')).toHaveTextContent(/tolérance/i);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('liste des jeux : statuts affichés', async () => {
    renderAs('owner', <Datasets />);
    expect(await screen.findByText('mesures_capteurs_demo.csv')).toBeInTheDocument();
    expect(screen.getAllByText('Protégé').length).toBeGreaterThan(0);
  });

  it('détail : bouton Protéger pour un jeu importé', async () => {
    renderAs('owner', <Routes><Route path="/datasets/:id" element={<DatasetDetail />} /></Routes>, '/datasets/mock-2');
    expect(await screen.findByRole('button', { name: 'Protéger' })).toBeInTheDocument();
  });

  it('distribution : le préparateur ne peut pas valider', async () => {
    await prepareDistribution('mock-1', ['a@demo.local'], 'mock-owner');
    renderAs('owner', <Distributions />);
    expect(await screen.findByRole('button', { name: 'Valider' })).toBeDisabled();
  });

  it('ma copie : téléchargement proposé au destinataire', async () => {
    renderAs('recipient', <MyCopy />);
    await waitFor(() => expect(screen.getByRole('button', { name: /télécharger ma copie/i })).toBeEnabled());
  });

  it('investigation : lancer désactivé sans fichier', () => {
    renderAs('investigator', <Investigations />);
    expect(screen.getByRole('button', { name: /lancer/i })).toBeDisabled();
  });

  it('verdict : 4 statuts avec texte (pas seulement la couleur)', () => {
    expect(Object.values(VERDICTS).map((v) => v.label)).toEqual(
      ['Marque reconnue', 'Copie identifiée', 'Indéterminé', 'Aucune marque détectée']);
    renderAs('owner', <VerdictCard r={{ id: '1', verdict: 'INDETERMINATE', reason: 'Trop peu de lignes.' }} />);
    expect(screen.getByRole('heading', { name: /indéterminé/i })).toBeInTheDocument();
  });

  it('admin et audit : tableaux affichés', async () => {
    renderAs('admin', <Admin />);
    expect(await screen.findByText('proprietaire@demo.local')).toBeInTheDocument();
    renderAs('auditor', <Audit />);
    expect(await screen.findByText('DATASET_IMPORTED')).toBeInTheDocument();
  });
});
