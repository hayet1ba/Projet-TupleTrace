// DONNÉES SIMULÉES. Aucune valeur ici n'est une vraie preuve cryptographique.
import type { Dataset, Role, SessionUser } from '../api/types';

/** Données métier simulées (le backend n'expose pas encore datasets, distributions...). */
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS === 'true';
/** Authentification simulée. Par défaut = USE_MOCKS ; mettre VITE_MOCK_AUTH=false pour le vrai login. */
export const USE_MOCK_AUTH = (import.meta.env.VITE_MOCK_AUTH ?? String(USE_MOCKS)) === 'true';

export const MOCK_USERS: Record<Role, SessionUser> = {
  owner: { id: 'mock-owner', displayName: 'Propriétaire (démo)', roles: ['owner'] },
  manager: { id: 'mock-manager', displayName: 'Gestionnaire (démo)', roles: ['manager'] },
  recipient: { id: 'mock-recipient', displayName: 'Destinataire (démo)', roles: ['recipient'] },
  investigator: { id: 'mock-investigator', displayName: 'Enquêteur (démo)', roles: ['investigator'] },
  admin: { id: 'mock-admin', displayName: 'Administrateur (démo)', roles: ['admin'] },
  auditor: { id: 'mock-auditor', displayName: 'Auditeur (démo)', roles: ['auditor'] },
};

export const MOCK_DATASETS: Dataset[] = [
  { id: 'mock-1', name: 'mesures_capteurs_demo.csv', version: 1, rows: 10000, status: 'PROTECTED' },
  { id: 'mock-2', name: 'releves_demo.csv', version: 2, rows: 1000, status: 'IMPORTED' },
];
