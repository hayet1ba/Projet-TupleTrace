// Contrôles de confort côté client. Le serveur refait TOUS les contrôles (SR-INT-08, SR-DIS-02).
export const MAX_BYTES = 50 * 1024 * 1024;
const SAMPLE_BYTES = 256 * 1024;

export interface ColumnProfile {
  name: string; type: 'number' | 'text';
  min?: number; max?: number; mean?: number; variance?: number;
  forbidden: boolean; reason?: string;
}
export interface CsvDraft {
  file: File; delimiter: string; sampleRows: number; columns: ColumnProfile[]; pkCandidates: string[]; pk?: string;
}

export function checkFile(f: { name: string; size: number }): string | null {
  if (!/\.csv$/i.test(f.name)) return 'Le fichier doit avoir l’extension .csv.';
  if (f.size === 0) return 'Le fichier est vide.';
  if (f.size > MAX_BYTES) return 'Le fichier dépasse 50 Mo.';
  return null;
}

export function parseRows(text: string, delimiter: string): string[][] {
  const rows: string[][] = []; let row: string[] = []; let cell = ''; let q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') q = false; else cell += c;
    } else if (c === '"') q = true;
    else if (c === delimiter) { row.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); cell = ''; if (row.some((x) => x !== '')) rows.push(row); row = [];
    } else cell += c;
  }
  row.push(cell); if (row.some((x) => x !== '')) rows.push(row);
  return rows;
}

/** Représentation canonique indépendante de la locale : 21,37 et 21.37 → 21.37 (SR-INT-13). */
export const toNumber = (v: string): number | null => {
  const t = v.trim().replace(/^(-?\d+),(\d+)$/, '$1.$2');
  return t !== '' && /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : null;
};

const FORBIDDEN_NAME = /(^|[_\s-])(id|uuid|code|date|dt|key|categorie|category)([_\s-]|$)|^id|_id$/i;

export function analyseCsv(text: string, file: File): { draft?: CsvDraft; errors: string[] } {
  const first = text.split(/\r?\n/, 1)[0] ?? '';
  const delimiter = [',', ';', '\t'].map((d) => [d, first.split(d).length] as const).sort((a, b) => b[1] - a[1])[0][0];
  const rows = parseRows(text, delimiter);
  const errors: string[] = [];
  if (rows.length < 2) return { errors: ['Le fichier doit contenir un en-tête et au moins une ligne.'] };
  const headers = rows[0].map((h) => h.trim());
  const data = rows.slice(1);
  if (headers.some((h) => h === '')) errors.push('Un en-tête est vide.');
  const seen = new Set<string>();
  for (const h of headers) {
    if (seen.has(h.toLowerCase())) errors.push(`En-tête dupliqué : « ${h} ».`);
    seen.add(h.toLowerCase());
  }
  if (errors.length) return { errors };

  const columns: ColumnProfile[] = headers.map((name, i) => {
    const vals = data.map((r) => r[i] ?? '').filter((v) => v.trim() !== '');
    const nums = vals.map(toNumber).filter((n): n is number => n !== null);
    const isNum = vals.length > 0 && nums.length / vals.length >= 0.95;
    const col: ColumnProfile = { name, type: isNum ? 'number' : 'text', forbidden: false };
    if (isNum) {
      const mean = nums.reduce((a, b) => a + b, 0) / nums.length;
      Object.assign(col, { min: Math.min(...nums), max: Math.max(...nums), mean,
        variance: nums.reduce((a, b) => a + (b - mean) ** 2, 0) / nums.length });
    }
    if (!isNum) { col.forbidden = true; col.reason = 'Colonne non numérique'; }
    else if (FORBIDDEN_NAME.test(name)) { col.forbidden = true; col.reason = 'Identifiant, code ou date'; }
    return col;
  });
  const pkCandidates = headers.filter((_, i) => {
    const vals = data.map((r) => (r[i] ?? '').trim());
    return vals.every((v) => v !== '') && new Set(vals).size === vals.length;
  });
  if (!pkCandidates.length) {
    return { errors: ['Aucune colonne ne peut servir de clé stable (valeurs uniques et non nulles) : import refusé.'] };
  }
  return { draft: { file, delimiter, sampleRows: data.length, columns, pkCandidates }, errors: [] };
}

/** Lit seulement le début du fichier : mémoire bornée, même pour 50 Mo. */
export async function readDraft(file: File): Promise<{ draft?: CsvDraft; errors: string[] }> {
  const bad = checkFile(file);
  if (bad) return { errors: [bad] };
  let text = await file.slice(0, SAMPLE_BYTES).text();
  if (file.size > SAMPLE_BYTES) text = text.slice(0, text.lastIndexOf('\n'));
  return analyseCsv(text, file);
}

// Brouillon en mémoire entre l'écran d'import et l'écran de schéma.
let current: CsvDraft | null = null;
export const setDraft = (d: CsvDraft | null) => { current = d; };
export const getDraft = () => current;
