import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import Login from './Login';

describe('Login', () => {
  it('affiche le formulaire et le bouton de connexion', () => {
    render(<MemoryRouter><AuthProvider><Login /></AuthProvider></MemoryRouter>);
    expect(screen.getByRole('heading', { name: /connexion/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /se connecter/i })).toBeEnabled();
  });
});
