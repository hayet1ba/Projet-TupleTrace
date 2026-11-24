import { describe, expect, it } from 'vitest';
import { NAV } from './roles';

describe('navigation par rôle', () => {
  const visible = (role: Parameters<(typeof NAV)[number]['roles']['includes']>[0]) =>
    NAV.filter((n) => n.roles.includes(role)).map((n) => n.to);

  it("l'administrateur n'a aucun accès aux jeux de données (SR-AUZ-06)", () => {
    expect(visible('admin')).not.toContain('/datasets');
  });
  it('le destinataire ne voit que sa copie', () => {
    expect(visible('recipient')).toEqual(['/', '/copies', '/account']);
  });
});
