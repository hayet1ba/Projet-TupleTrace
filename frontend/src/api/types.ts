// Contrats TypeScript du recueil (§3). À aligner avec le backend de Cheima.
export type Role = 'owner' | 'manager' | 'recipient' | 'investigator' | 'admin' | 'auditor';

export interface SessionUser {
  id: string;
  displayName: string;
  /** Un compte peut cumuler plusieurs rôles (seul ADMINISTRATEUR est exclusif, cf. V1). */
  roles: Role[];
  /** Fin de validité du jeton (ms). Pas de refresh côté API : après 15 min, nouvelle connexion. */
  expiresAt?: number;
  /** Date de la dernière preuve TOTP (claim auth_time), pour la ré-authentification ≤ 5 min. */
  strongAuthAt?: number;
}

export type DatasetStatus = 'IMPORTED' | 'PROTECTED' | 'FAILED';
export interface Dataset {
  id: string;
  name: string;
  version: number;
  rows: number;
  status: DatasetStatus;
}

export type DistributionState = 'PENDING' | 'VALIDATED' | 'FAILED' | 'REVOKED';

/** Les 4 statuts de verdict imposés (BF-INV-04). */
export type Verdict = 'MARK_RECOGNIZED' | 'COPY_IDENTIFIED' | 'INDETERMINATE' | 'NO_MARK';

/** RFC 9457 */
export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  traceId?: string;
}

export interface ColumnConfig { name: string; tolerance: number }
export interface Distribution {
  id: string; datasetId: string; datasetName: string; recipients: string[];
  state: DistributionState; preparedBy: string;
}
export interface MyCopy { id: string; datasetName: string; state: DistributionState }
/** Les valeurs n, k, p, seuil viennent TOUJOURS du serveur (SR-INT-10). */
export interface InvestigationResult {
  id: string; verdict: Verdict; reason: string; n?: number; k?: number; pValue?: number; threshold?: number;
}
export interface AuditEvent { id: string; at: string; actor: string; action: string }
export interface Account { id: string; email: string; role: Role; active: boolean }
