import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { expect } from 'vitest';
import { axe } from 'vitest-axe';
import * as matchers from 'vitest-axe/matchers';
import type { Role } from '../api/types';
import { AuthProvider } from '../auth/AuthContext';
import { MOCK_USERS } from '../mocks/data';

expect.extend(matchers);

export function renderAs(role: Role, ui: ReactElement, path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}><AuthProvider initialUser={MOCK_USERS[role]}>{ui}</AuthProvider></MemoryRouter>,
  );
}
export { axe };
