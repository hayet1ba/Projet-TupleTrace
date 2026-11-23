import { describe, expect, it } from 'vitest';
import { analyseCsv, checkFile, toNumber } from './csv';

const f = new File(['x'], 'a.csv');
describe('contrôles CSV', () => {
  it('refuse extension, fichier vide et > 50 Mo', () => {
    expect(checkFile({ name: 'a.txt', size: 10 })).toMatch(/\.csv/);
    expect(checkFile({ name: 'a.csv', size: 0 })).toMatch(/vide/);
    expect(checkFile({ name: 'a.csv', size: 51 * 1024 * 1024 })).toMatch(/50 Mo/);
    expect(checkFile({ name: 'a.csv', size: 10 })).toBeNull();
  });
  it('refuse les en-têtes dupliqués', () => {
    expect(analyseCsv('id,temp,Temp\n1,2,3', f).errors[0]).toMatch(/dupliqué/);
  });
  it('refuse un fichier sans clé stable', () => {
    expect(analyseCsv('a,b\n1,2\n1,2', f).errors[0]).toMatch(/clé stable/);
  });
  it('marque la colonne numérique et grise le reste', () => {
    const { draft } = analyseCsv('record_id;sensor;temperature\n1;S1;21,37\n2;S2;22,5\n', f);
    const by = Object.fromEntries(draft!.columns.map((c) => [c.name, c]));
    expect(by.record_id.forbidden).toBe(true);
    expect(by.sensor.forbidden).toBe(true);
    expect(by.temperature.forbidden).toBe(false);
    expect(draft!.pkCandidates).toContain('record_id');
  });
  it('lit 21,37 et 21.37 comme la même valeur', () => {
    expect(toNumber('21,37')).toBe(toNumber('21.37'));
  });
});
