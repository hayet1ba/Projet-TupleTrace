// Une seule couche d'accès : mocks si VITE_USE_MOCKS=true, vraies routes du recueil (§3) sinon.
import type { Account, AuditEvent, ColumnConfig, Dataset, Distribution, InvestigationResult, MyCopy } from './types';
import { api, apiBlob } from './client';
import { MOCK_DATASETS, USE_MOCKS } from '../mocks/data';
import type { CsvDraft } from '../lib/csv';

const ok = <T,>(v: T) => Promise.resolve(structuredClone(v));
let datasets: Dataset[] = [...MOCK_DATASETS];
let distributions: Distribution[] = [];
let seq = 100;

export const listDatasets = (): Promise<Dataset[]> => USE_MOCKS ? ok(datasets) : api('/datasets');
export const getDataset = async (id: string): Promise<Dataset> =>
  USE_MOCKS ? ok(datasets.find((d) => d.id === id)!) : api(`/datasets/${id}`);

export async function createDataset(draft: CsvDraft, pk: string, columns: ColumnConfig[]): Promise<Dataset> {
  if (USE_MOCKS) {
    const d: Dataset = { id: `mock-${++seq}`, name: draft.file.name, version: 1, rows: draft.sampleRows, status: 'IMPORTED' };
    datasets = [d, ...datasets];
    return ok(d);
  }
  const fd = new FormData();
  fd.append('file', draft.file);
  fd.append('config', JSON.stringify({ pk, columns }));
  return api('/datasets', { method: 'POST', body: fd });
}

export async function protectDataset(id: string): Promise<Dataset> {
  if (USE_MOCKS) { datasets = datasets.map((d) => d.id === id ? { ...d, status: 'PROTECTED' } : d); return getDataset(id); }
  return api(`/datasets/${id}/protect`, { method: 'POST' });
}

export const listDistributions = (): Promise<Distribution[]> => USE_MOCKS ? ok(distributions) : api('/distributions');

export async function prepareDistribution(datasetId: string, recipients: string[], userId: string): Promise<Distribution> {
  if (USE_MOCKS) {
    const ds = datasets.find((d) => d.id === datasetId)!;
    const d: Distribution = { id: `dist-${++seq}`, datasetId, datasetName: ds.name, recipients, state: 'PENDING', preparedBy: userId };
    distributions = [d, ...distributions];
    return ok(d);
  }
  return api('/distributions', { method: 'POST', body: JSON.stringify({ datasetId, recipients }) });
}

export async function validateDistribution(id: string): Promise<Distribution> {
  if (USE_MOCKS) {
    distributions = distributions.map((d) => d.id === id ? { ...d, state: 'VALIDATED' } : d);
    return ok(distributions.find((d) => d.id === id)!);
  }
  return api(`/distributions/${id}/validate`, { method: 'POST' });
}

export const getMyCopy = (): Promise<MyCopy | null> =>
  USE_MOCKS ? ok({ id: 'copy-demo', datasetName: 'mesures_capteurs_demo.csv', state: 'VALIDATED' } as MyCopy) : api('/copies/mine');

export const downloadCopy = async (id: string): Promise<Blob> =>
  USE_MOCKS ? new Blob(['COPIE SIMULÉE : aucune donnée réelle\n'], { type: 'text/csv' }) : apiBlob(`/copies/${id}/download`);

export async function submitInvestigation(file: File): Promise<InvestigationResult> {
  if (USE_MOCKS) {
    return ok({ id: 'inv-demo', verdict: 'INDETERMINATE', reason: 'Résultat simulé : extrait trop petit pour conclure.' } as InvestigationResult);
  }
  const fd = new FormData();
  fd.append('extract', file); // le client n'envoie que l'extrait (SR-INT-10)
  return api('/investigations', { method: 'POST', body: fd });
}
export const downloadReport = (id: string): Promise<Blob> => apiBlob(`/investigations/${id}/report`);

export const listAccounts = (): Promise<Account[]> => USE_MOCKS
  ? ok([{ id: 'a1', email: 'proprietaire@demo.local', role: 'owner', active: true }, { id: 'a2', email: 'gestionnaire@demo.local', role: 'manager', active: false }] as Account[])
  : api('/admin/accounts');
export const listAudit = (): Promise<AuditEvent[]> => USE_MOCKS
  ? ok([{ id: 'e1', at: '2026-11-22T10:00:00Z', actor: 'mock-owner', action: 'DATASET_IMPORTED' }] as AuditEvent[])
  : api('/audit/events');
