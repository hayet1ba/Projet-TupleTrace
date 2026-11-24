import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import Login from './Login';

afterEach(() => vi.restoreAllMocks());

describe('contrat de connexion avec le backend', () => {
  it('envoie {email, password}, puis demande le TOTP sur 202', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ next: 'totp' }), { status: 202 }));
    render(<MemoryRouter><AuthProvider><Login /></AuthProvider></MemoryRouter>);
    await userEvent.type(screen.getByLabelText(/adresse e-mail/i), 'a@demo.local');
    await userEvent.type(screen.getByLabelText(/mot de passe/i), 'un-mot-de-passe-long');
    await userEvent.click(screen.getByRole('button', { name: /se connecter/i }));
    expect(await screen.findByLabelText(/code totp/i)).toBeInTheDocument();
    expect(JSON.parse(spy.mock.calls[0][1]!.body as string)).toEqual({ email: 'a@demo.local', password: 'un-mot-de-passe-long' });
  });

  it('affiche un message uniforme sur 401', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 401 }));
    render(<MemoryRouter><AuthProvider><Login /></AuthProvider></MemoryRouter>);
    await userEvent.type(screen.getByLabelText(/adresse e-mail/i), 'a@demo.local');
    await userEvent.type(screen.getByLabelText(/mot de passe/i), 'x');
    await userEvent.click(screen.getByRole('button', { name: /se connecter/i }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/identifiants incorrects/i));
  });
});
