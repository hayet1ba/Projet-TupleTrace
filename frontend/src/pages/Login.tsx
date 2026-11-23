import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { ApiError, errorMessage } from '../api/client';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ROLE_LABELS } from '../auth/roles';
import { USE_MOCK_AUTH } from '../mocks/data';

export default function Login() {
  const { user, login, notice } = useAuth();
  const nav = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [otp, setOtp] = useState('');
  const [needOtp, setNeedOtp] = useState(false);
  const [mockRole, setMockRole] = useState<Role>('owner');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (user) return <Navigate to="/" replace />;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!USE_MOCK_AUTH && (!email.includes('@') || password === '')) {
      setError('Saisissez votre adresse e-mail et votre mot de passe.');
      return;
    }
    setBusy(true);
    try {
      const out = await login({ email, password, otp: needOtp ? otp : undefined, mockRole });
      if (out === 'otp_required') setNeedOtp(true);
      else nav('/', { replace: true });
    } catch (err) {
      // Réponse uniforme du serveur : on ne dit jamais quel champ est faux.
      if (err instanceof ApiError && err.kind === 'unauthenticated') setError('Identifiants incorrects, code invalide ou compte indisponible.');
      else if (err instanceof ApiError && err.kind === 'rate_limited') setError('Trop de tentatives ou service saturé. Réessayez plus tard.');
      else setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <div className="login-brand">
        <div className="brand">TupleTrace</div>
        <p>Plateforme sécurisée de partage de jeux de données traçables.</p>
        <p className="sub">Tatouage, stéganographie et empreinte numérique de données tabulaires.</p>
      </div>
      <div className="login-panel">
      <form onSubmit={(e) => void submit(e)} noValidate aria-labelledby="t">
        <h1 id="t">Connexion à TupleTrace</h1>
        {notice && <p className="banner warn" role="status">{notice}</p>}
        {USE_MOCK_AUTH ? (
          <>
            <p className="banner warn">Mode démonstration : aucune authentification réelle. Choisissez un rôle.</p>
            <label>Rôle simulé
              <select value={mockRole} onChange={(e) => setMockRole(e.target.value as Role)}>
                {(Object.keys(ROLE_LABELS) as Role[]).map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
              </select>
            </label>
          </>
        ) : (
          <>
            <label>Adresse e-mail
              <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required readOnly={needOtp} />
            </label>
            <label>Mot de passe
              <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required readOnly={needOtp} />
            </label>
            {needOtp && (
              <label>Code TOTP (6 chiffres)
                <input inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={otp} onChange={(e) => setOtp(e.target.value)} autoFocus required />
              </label>
            )}
            {/* TODO : widget ALTCHA (aucun endpoint de défi côté backend pour l'instant). */}
          </>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? 'Connexion…' : needOtp ? 'Valider le code' : 'Se connecter'}</button>
      </form>
      </div>
    </main>
  );
}
