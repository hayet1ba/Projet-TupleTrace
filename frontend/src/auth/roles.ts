import type { Role } from '../api/types';

export const ROLE_LABELS: Record<Role, string> = {
  owner: 'Propriétaire',
  manager: 'Gestionnaire de distribution',
  recipient: 'Destinataire',
  investigator: 'Enquêteur',
  admin: 'Administrateur technique',
  auditor: 'Auditeur',
};

/** Noms de rôles du backend (table role_assignment) -> rôles de l'interface. */
export const BACKEND_ROLES: Record<string, Role> = {
  PROPRIETAIRE: 'owner', GESTIONNAIRE: 'manager', DESTINATAIRE: 'recipient',
  ENQUETEUR: 'investigator', ADMINISTRATEUR: 'admin', AUDITEUR: 'auditor',
};
/** Rôles soumis au TOTP (SR-AUT-02, privileged_role en V7). */
export const PRIVILEGED: Role[] = ['owner', 'admin', 'auditor'];
const ALL: Role[] = ['owner', 'manager', 'recipient', 'investigator', 'admin', 'auditor'];

export const hasAny = (user: { roles: Role[] } | null, roles?: Role[]) =>
  !roles || !!user?.roles.some((r) => roles.includes(r));
export const roleText = (user: { roles: Role[] }) =>
  user.roles.map((r) => ROLE_LABELS[r]).join(', ') || 'Rôle non communiqué';

export interface NavItem { to: string; label: string; roles: Role[] }

// Reflète la matrice des droits (CdC §5). Le serveur reste l'autorité (F1).
export const NAV: NavItem[] = [
  { to: '/', label: 'Tableau de bord', roles: ['owner', 'manager', 'recipient', 'investigator', 'admin', 'auditor'] },
  { to: '/datasets', label: 'Jeux de données', roles: ['owner'] },
  { to: '/datasets/import', label: 'Importer un CSV', roles: ['owner'] },
  { to: '/distributions', label: 'Distributions', roles: ['owner', 'manager'] },
  { to: '/copies', label: 'Ma copie', roles: ['recipient'] },
  { to: '/investigations', label: 'Investigations', roles: ['owner', 'investigator'] },
  { to: '/admin', label: 'Administration', roles: ['admin'] },
  { to: '/audit', label: "Journal d'audit", roles: ['auditor'] },
  { to: '/account', label: 'Mon compte', roles: ALL },
];
